package dev.veil.proxy.cache;

import dev.veil.proxy.config.Crypto;
import dev.veil.proxy.config.ProxyProperties;
import dev.veil.proxy.state.Embeddings;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.protocol.ProtocolVersion;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.NestedMultiOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.ProtocolKeyword;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Semantic cache on Redis's built-in vector search (RediSearch, HNSW, cosine). The key is the embedding of the
 * REDACTED prompt, scoped by organisation, model and conversation context. Because placeholders are positional,
 * "Write to [PERSON_1] about a refund" from two different customers is the same cache entry, and each user gets the
 * answer rehydrated with their own customer's name. Sensitive values never reach the cache.
 */
@Component
public class SemanticCache {

    private static final Logger log = LoggerFactory.getLogger(SemanticCache.class);
    private static final String INDEX = "veil-semcache";
    private static final String PREFIX = "veil:cache:";

    private enum Cmd implements ProtocolKeyword {
        FT_CREATE("FT.CREATE"), FT_SEARCH("FT.SEARCH"), HSET("HSET"), PEXPIRE("PEXPIRE"), FT_INFO("FT.INFO");
        private final byte[] bytes;

        Cmd(String name) {
            this.bytes = name.getBytes(StandardCharsets.US_ASCII);
        }

        @Override
        public byte[] getBytes() {
            return bytes;
        }
    }

    public record Hit(String answerJson, double distance) {
    }

    private final LettuceConnectionFactory factory;
    private final Embeddings embeddings;
    private final ProxyProperties props;
    private final Counter hits;
    private final Counter misses;
    private volatile StatefulRedisConnection<byte[], byte[]> connection;
    private volatile RedisClient client;
    private volatile boolean available;

    public SemanticCache(LettuceConnectionFactory factory, Embeddings embeddings, ProxyProperties props, MeterRegistry meters) {
        this.factory = factory;
        this.embeddings = embeddings;
        this.props = props;
        this.hits = Counter.builder("veil.cache").tag("result", "hit").register(meters);
        this.misses = Counter.builder("veil.cache").tag("result", "miss").register(meters);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        try {
            // A dedicated RESP2 connection: with RESP3, FT.SEARCH replies are maps whose shape differs between versions.
            RedisURI uri = RedisURI.create(factory.getHostName(), factory.getPort());
            if (factory.getPassword() != null && !factory.getPassword().isEmpty()) uri.setPassword(factory.getPassword().toCharArray());
            client = RedisClient.create(uri);
            client.setOptions(ClientOptions.builder().protocolVersion(ProtocolVersion.RESP2).build());
            connection = client.connect(ByteArrayCodec.INSTANCE);
            createIndex();
            available = true;
        } catch (RuntimeException e) {
            log.warn("Semantic cache disabled (Redis without vector search?): {}", e.getMessage());
        }
    }

    private void createIndex() {
        try {
            dispatch(Cmd.FT_CREATE, new CommandArgs<>(ByteArrayCodec.INSTANCE).add(INDEX).add("ON").add("HASH")
                    .add("PREFIX").add(1).add(PREFIX).add("SCHEMA")
                    .add("org").add("TAG").add("model").add("TAG").add("ctx").add("TAG")
                    .add("vec").add("VECTOR").add("HNSW").add(6).add("TYPE").add("FLOAT32").add("DIM").add(Embeddings.DIMENSIONS)
                    .add("DISTANCE_METRIC").add("COSINE"));
            log.info("Created semantic cache index {}", INDEX);
        } catch (RuntimeException e) {
            if (!String.valueOf(e.getMessage()).contains("already exists")) throw e;
        }
    }

    public boolean available() {
        return available;
    }

    /** Looks for a cached answer to a prompt this close to an earlier one. Never fails the request. */
    public Mono<Optional<Hit>> lookup(String orgId, String model, String context, String prompt) {
        if (!available) return Mono.just(Optional.empty());
        return Mono.fromCallable(() -> {
                    byte[] vec = vector(embeddings.embed(prompt));
                    String query = "(@org:{" + tag(orgId) + "} @model:{" + tag(model) + "} @ctx:{" + tag(context) + "})=>[KNN 1 @vec $B AS dist]";
                    List<Object> r = dispatch(Cmd.FT_SEARCH, new CommandArgs<>(ByteArrayCodec.INSTANCE).add(INDEX).add(query)
                            .add("PARAMS").add(2).add("B").add(vec).add("SORTBY").add("dist").add("RETURN").add(2).add("dist").add("answer")
                            .add("DIALECT").add(2));
                    Optional<Hit> hit = parse(r);
                    (hit.isPresent() ? hits : misses).increment();
                    return hit;
                }).subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.debug("Semantic cache lookup failed: {}", e.toString());
                    return Mono.just(Optional.empty());
                });
    }

    public Mono<Void> store(String orgId, String model, String context, String prompt, String answerJson) {
        if (!available) return Mono.empty();
        return Mono.fromRunnable(() -> {
                    byte[] key = (PREFIX + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
                    dispatch(Cmd.HSET, new CommandArgs<>(ByteArrayCodec.INSTANCE).add(key).add("org").add(tag(orgId))
                            .add("model").add(tag(model)).add("ctx").add(tag(context))
                            .add("vec").add(vector(embeddings.embed(prompt))).add("answer").add(answerJson));
                    dispatch(Cmd.PEXPIRE, new CommandArgs<>(ByteArrayCodec.INSTANCE).add(key).add(props.semanticCacheTtl().toMillis()));
                }).subscribeOn(Schedulers.boundedElastic()).then()
                .onErrorResume(e -> {
                    log.debug("Semantic cache store failed: {}", e.toString());
                    return Mono.empty();
                });
    }

    private Optional<Hit> parse(List<Object> r) {
        if (r == null || r.size() < 3) return Optional.empty();
        // Depending on the protocol version the document's fields come nested or flattened after its key.
        List<?> fields = r.get(2) instanceof List<?> nested ? nested : r.subList(2, r.size());
        String dist = null;
        String answer = null;
        for (int i = 0; i + 1 < fields.size(); i += 2) {
            String name = new String((byte[]) fields.get(i), StandardCharsets.UTF_8);
            String value = new String((byte[]) fields.get(i + 1), StandardCharsets.UTF_8);
            if (name.equals("dist")) dist = value;
            if (name.equals("answer")) answer = value;
        }
        if (dist == null || answer == null) return Optional.empty();
        double d = Double.parseDouble(dist);
        return d <= props.semanticCacheDistance() ? Optional.of(new Hit(answer, d)) : Optional.empty();
    }

    private List<Object> dispatch(Cmd cmd, CommandArgs<byte[], byte[]> args) {
        return connection.sync().dispatch(cmd, new NestedMultiOutput<>(ByteArrayCodec.INSTANCE), args);
    }

    /** Tags are hashed so model names with dots or dashes need no escaping in queries. */
    private static String tag(String value) {
        return Crypto.sha256(value).substring(0, 16);
    }

    private static byte[] vector(float[] v) {
        ByteBuffer b = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : v) b.putFloat(f);
        return b.array();
    }

    @PreDestroy
    void close() {
        if (connection != null) connection.close();
        if (client != null) client.shutdown();
    }
}
