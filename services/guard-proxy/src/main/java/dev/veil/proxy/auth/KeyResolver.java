package dev.veil.proxy.auth;

import dev.veil.contracts.KeyEvent;
import dev.veil.contracts.KeyInfo;
import dev.veil.contracts.Topics;
import dev.veil.proxy.config.Crypto;
import dev.veil.proxy.config.JsonCodec;
import dev.veil.proxy.config.ProxyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a virtual key to its team and limits. Three tiers: an in-process map (30 s), Redis (5 min, shared by
 * all proxy instances) and the control plane (only on a miss). Unknown keys are negatively cached so a brute-force
 * attempt can't hammer the control plane. Revocations arrive through Kafka and evict all tiers at once.
 */
@Component
public class KeyResolver {

    private static final Logger log = LoggerFactory.getLogger(KeyResolver.class);
    private static final Duration LOCAL_TTL = Duration.ofSeconds(30);
    private static final Duration REDIS_TTL = Duration.ofMinutes(5);
    private static final Duration NEGATIVE_TTL = Duration.ofSeconds(30);
    /** How long a known key may be served from memory when neither Redis nor the control plane can confirm it. */
    private static final Duration STALE_IF_ERROR = Duration.ofHours(24);
    private static final String UNKNOWN = "{\"unknown\":true}";

    private record Cached(KeyInfo info, long expiresAt) {
    }

    private final Map<String, Cached> local = new ConcurrentHashMap<>();
    private final ReactiveStringRedisTemplate redis;
    private final WebClient controlPlane;
    private final JsonCodec json;

    public KeyResolver(ReactiveStringRedisTemplate redis, WebClient.Builder web, ProxyProperties props, JsonCodec json) {
        this.redis = redis;
        this.controlPlane = web.baseUrl(props.controlPlaneUrl()).build();
        this.json = json;
    }

    /** Reads the key from {@code Authorization: Bearer} or {@code x-api-key} (Anthropic style). */
    public static String extract(ServerHttpRequest request) {
        String auth = request.getHeaders().getFirst("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) return auth.substring(7).trim();
        String apiKey = request.getHeaders().getFirst("x-api-key");
        return apiKey == null ? null : apiKey.trim();
    }

    public Mono<KeyInfo> resolve(String key, String requiredKind) {
        if (key == null || key.isBlank()) {
            return Mono.error(ProxyException.unauthorized("Missing API key. Send your Veil virtual key as 'Authorization: Bearer vk_...'"));
        }
        if (!key.startsWith("vk_")) {
            return Mono.error(ProxyException.unauthorized("That is not a Veil virtual key. Ask your admin for one (they start with vk_)."));
        }
        String hash = Crypto.sha256(key);
        return lookup(hash).flatMap(info -> {
            if (info.keyId() == null) return Mono.error(ProxyException.unauthorized("Invalid API key"));
            if (info.revoked()) return Mono.error(ProxyException.unauthorized("This API key has been revoked"));
            if (requiredKind != null && !requiredKind.equals(info.kind())) {
                return Mono.error(new ProxyException(HttpStatus.FORBIDDEN, "invalid_request_error", "wrong_key_kind",
                        "This endpoint needs a " + requiredKind.toLowerCase() + " key"));
            }
            return Mono.just(info);
        });
    }

    private Mono<KeyInfo> lookup(String hash) {
        Cached c = local.get(hash);
        if (c != null && c.expiresAt() > System.currentTimeMillis()) return Mono.just(c.info());
        return fetch(hash).onErrorResume(ProxyException.class, e -> {
            // Stale-if-error: a control-plane outage must not take every app down. Revocations still evict at once.
            if (c != null && c.info().keyId() != null && c.expiresAt() + STALE_IF_ERROR.toMillis() > System.currentTimeMillis()) {
                log.warn("Control plane unavailable; serving key {} from memory", c.info().keyId());
                return Mono.just(c.info());
            }
            return Mono.error(e);
        });
    }

    private Mono<KeyInfo> fetch(String hash) {
        String redisKey = "veil:key:" + hash;
        return redis.opsForValue().get(redisKey)
                .onErrorResume(e -> {
                    log.warn("Redis unavailable for key lookup, asking the control plane: {}", e.toString());
                    return Mono.empty();
                })
                .flatMap(s -> Mono.just(parse(s)))
                .switchIfEmpty(Mono.defer(() -> fromControlPlane(hash)
                        .flatMap(info -> {
                            boolean unknown = info.keyId() == null;
                            return redis.opsForValue().set(redisKey, unknown ? UNKNOWN : json.write(info), unknown ? NEGATIVE_TTL : REDIS_TTL)
                                    .onErrorReturn(false).thenReturn(info);
                        })))
                .doOnNext(info -> local.put(hash, new Cached(info, System.currentTimeMillis() + LOCAL_TTL.toMillis())));
    }

    private KeyInfo parse(String s) {
        return UNKNOWN.equals(s) ? unknown() : json.read(s, KeyInfo.class);
    }

    private Mono<KeyInfo> fromControlPlane(String hash) {
        return controlPlane.get().uri("/internal/keys/{hash}", hash).retrieve().bodyToMono(KeyInfo.class)
                .timeout(Duration.ofSeconds(3))
                .onErrorResume(WebClientResponseException.NotFound.class, e -> Mono.just(unknown()))
                .onErrorMap(e -> !(e instanceof ProxyException), e -> new ProxyException(HttpStatus.SERVICE_UNAVAILABLE,
                        "server_error", "control_plane_unavailable", "Veil can't verify keys right now; try again shortly"));
    }

    private static KeyInfo unknown() {
        return new KeyInfo(null, null, null, null, null, null, 0, 0, true);
    }

    /** Revoked or changed keys: drop every cached copy immediately. */
    @KafkaListener(topics = Topics.KEYS, containerFactory = "broadcastFactory")
    public void onKeyEvent(String payload) {
        KeyEvent e = json.read(payload, KeyEvent.class);
        local.remove(e.keyHash());
        redis.delete("veil:key:" + e.keyHash()).onErrorReturn(0L).subscribe();
        log.info("Key {} {}: caches evicted", e.keyId(), e.change().toLowerCase());
    }

    public int localCacheSize() {
        return local.size();
    }
}
