package dev.veil.proxy.state;

import dev.veil.contracts.OrgConfig;
import dev.veil.contracts.Topics;
import dev.veil.detect.DictionaryDetector;
import dev.veil.detect.Detector;
import dev.veil.detect.ScanEngine;
import dev.veil.detect.SemanticDetector;
import dev.veil.proxy.config.Crypto;
import dev.veil.proxy.config.JsonCodec;
import dev.veil.proxy.config.ProxyProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The proxy's in-memory view of every organisation's configuration. Pushed through the compacted Kafka topic
 * (each instance reads it from the beginning on start); at start-up it also pulls snapshots over HTTP so a proxy
 * can serve even if Kafka is briefly unavailable. Nothing on the request path touches a database.
 */
@Component
public class ConfigStore {

    private static final Logger log = LoggerFactory.getLogger(ConfigStore.class);

    private final Map<String, OrgState> orgs = new ConcurrentHashMap<>();
    private final JsonCodec json;
    private final Crypto crypto;
    private final Embeddings embeddings;
    private final ProxyProperties props;
    private final WebClient controlPlane;
    private final List<Detector> standardDetectors = ScanEngine.standardDetectors();

    public ConfigStore(JsonCodec json, Crypto crypto, Embeddings embeddings, ProxyProperties props,
                       WebClient.Builder web, MeterRegistry meters) {
        this.json = json;
        this.crypto = crypto;
        this.embeddings = embeddings;
        this.props = props;
        this.controlPlane = web.baseUrl(props.controlPlaneUrl()).build();
        Gauge.builder("veil.config.orgs", orgs, Map::size).register(meters);
    }

    public Optional<OrgState> get(String orgId) {
        return Optional.ofNullable(orgs.get(orgId));
    }

    public Map<String, Long> versions() {
        Map<String, Long> v = new HashMap<>();
        orgs.forEach((k, s) -> v.put(k, s.version()));
        return v;
    }

    @KafkaListener(topics = Topics.CONFIG, containerFactory = "broadcastFactory")
    public void onSnapshot(String payload) {
        apply(json.read(payload, OrgConfig.class), "kafka");
    }

    /** Pulls every organisation's current snapshot once the app is up (retried in the background). */
    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        controlPlane.get().uri("/internal/orgs").retrieve().bodyToFlux(String.class)
                .flatMap(org -> controlPlane.get().uri("/internal/config/{org}", org).retrieve().bodyToMono(String.class))
                .retryWhen(reactor.util.retry.Retry.backoff(30, Duration.ofSeconds(2)).maxBackoff(Duration.ofSeconds(15)))
                .subscribe(s -> apply(json.read(s, OrgConfig.class), "http"),
                        e -> log.warn("Could not bootstrap config over HTTP; relying on Kafka: {}", e.toString()));
    }

    /** Applies a snapshot if it's newer than what we have. Synchronised per org to compile once. */
    public synchronized void apply(OrgConfig config, String via) {
        OrgState current = orgs.get(config.orgId());
        if (current != null && current.version() >= config.version()) return;
        long t0 = System.currentTimeMillis();
        orgs.put(config.orgId(), compile(config));
        log.info("Config for org {} now at v{} (policy v{}, via {}, compiled in {} ms)", config.orgId(), config.version(),
                config.policy().version(), via, System.currentTimeMillis() - t0);
    }

    OrgState compile(OrgConfig config) {
        List<Detector> extra = new ArrayList<>();
        if (!config.dictionary().isEmpty()) {
            extra.add(new DictionaryDetector(config.dictionary().stream()
                    .map(t -> new DictionaryDetector.Term(t.term(), t.label())).toList()));
        }
        ScanEngine withoutSemantic = new ScanEngine(standardDetectors).with(extra);
        ScanEngine engine = withoutSemantic;
        if (props.semanticDetection()) {
            Map<String, String> labels = new HashMap<>();
            List<SemanticDetector.Example> examples = new ArrayList<>();
            for (var t : config.topics()) {
                labels.put(t.label(), t.description());
                examples.add(new SemanticDetector.Example(t.label(), t.example()));
            }
            engine = withoutSemantic.with(List.of(SemanticDetector.withDefaults(embeddings, examples, labels)));
        }
        Map<String, String> keys = new HashMap<>();
        for (var p : config.providers()) {
            if (p.apiKeyCiphertext() != null) {
                try {
                    keys.put(p.id(), crypto.decrypt(p.apiKeyCiphertext()));
                } catch (RuntimeException e) {
                    log.error("Provider {} of org {}: {}", p.id(), config.orgId(), e.getMessage());
                }
            }
        }
        return new OrgState(config, engine, withoutSemantic, Map.copyOf(keys));
    }
}
