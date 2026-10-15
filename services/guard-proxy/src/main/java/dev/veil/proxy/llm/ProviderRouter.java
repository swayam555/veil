package dev.veil.proxy.llm;

import dev.veil.contracts.OrgConfig;
import dev.veil.proxy.auth.ProxyException;
import dev.veil.proxy.config.ProxyProperties;
import dev.veil.proxy.state.OrgState;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sends a request along a route's targets in order. Each provider has its own circuit breaker: after repeated
 * failures it is skipped for 15 s instead of making every request wait for a timeout. A stream can fail over only
 * before its first chunk; after that the client already has part of an answer.
 */
@Component
public class ProviderRouter {

    private static final Logger log = LoggerFactory.getLogger(ProviderRouter.class);

    /** What happened on each attempt, for the decision trace and the audit log. */
    public record Attempt(String providerId, String providerName, String model, String outcome, long ms) {
    }

    public record Target(OrgConfig.ProviderConfig provider, String upstreamModel) {
    }

    public record Routed<T>(T result, Target servedBy, List<Attempt> attempts) {
    }

    /** A stream whose serving provider is known only once the first chunk arrives. */
    public record RoutedStream(Flux<ObjectNode> flux, java.util.function.Supplier<Target> servedBy, List<Attempt> attempts) {
    }

    private final Map<OrgConfig.ProviderKind, LlmProvider> providers = new EnumMap<>(OrgConfig.ProviderKind.class);
    private final CircuitBreakerRegistry breakers;
    private final ProxyProperties props;

    public ProviderRouter(List<LlmProvider> all, ProxyProperties props) {
        all.forEach(p -> providers.put(p.kind(), p));
        this.props = props;
        this.breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(20).minimumNumberOfCalls(5).failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(15)).permittedNumberOfCallsInHalfOpenState(3)
                .recordException(e -> !(e instanceof UpstreamException u) || u.retryable())
                .build());
    }

    /** The targets for a model alias; for ROUTE_LOCAL, only on-premises providers. */
    public List<Target> targets(OrgState org, String model, boolean localOnly) {
        OrgConfig config = org.config();
        List<Target> out = new ArrayList<>();
        if (localOnly) {
            OrgConfig.RouteConfig requested = config.route(model);
            if (requested != null) {
                for (var t : requested.targets()) {
                    var p = config.provider(t.providerId());
                    if (p != null && p.enabled() && p.local()) out.add(new Target(p, t.upstreamModel()));
                }
            }
            if (out.isEmpty()) {
                // Use the first route whose first target is local, e.g. "local-llama".
                for (var r : config.routes()) {
                    var p = r.targets().isEmpty() ? null : config.provider(r.targets().getFirst().providerId());
                    if (p != null && p.enabled() && p.local()) {
                        out.add(new Target(p, r.targets().getFirst().upstreamModel()));
                        break;
                    }
                }
            }
            if (out.isEmpty()) {
                throw new ProxyException(HttpStatus.FORBIDDEN, "veil_blocked", "no_local_model",
                        "This request must stay on premises, but no on-premises model is configured");
            }
            return out;
        }
        OrgConfig.RouteConfig route = config.route(model);
        if (route == null) {
            throw new ProxyException(HttpStatus.NOT_FOUND, "invalid_request_error", "model_not_found",
                    "The model '" + model + "' is not available through Veil. Available: "
                            + String.join(", ", config.routes().stream().map(OrgConfig.RouteConfig::model).toList()));
        }
        for (var t : route.targets()) {
            var p = config.provider(t.providerId());
            if (p != null && p.enabled()) out.add(new Target(p, t.upstreamModel()));
        }
        if (out.isEmpty()) {
            throw new ProxyException(HttpStatus.SERVICE_UNAVAILABLE, "server_error", "no_provider", "No enabled provider for " + model);
        }
        return out;
    }

    public Mono<Routed<Completion>> complete(OrgState org, List<Target> targets, ObjectNode body) {
        List<Attempt> attempts = Collections.synchronizedList(new ArrayList<>());
        return tryComplete(org, targets, 0, body, attempts);
    }

    private Mono<Routed<Completion>> tryComplete(OrgState org, List<Target> targets, int i, ObjectNode body, List<Attempt> attempts) {
        if (i >= targets.size()) return Mono.error(exhausted(attempts));
        Target t = targets.get(i);
        long t0 = System.currentTimeMillis();
        LlmProvider provider = providers.get(t.provider().kind());
        ProviderCall call = new ProviderCall(t.provider(), org.providerKeys().get(t.provider().id()), t.upstreamModel(), body);
        return Mono.defer(() -> provider.complete(call))
                .timeout(props.upstreamTimeout())
                .transformDeferred(CircuitBreakerOperator.of(breaker(org, t)))
                .map(c -> {
                    attempts.add(new Attempt(t.provider().id(), t.provider().name(), t.upstreamModel(), "ok", System.currentTimeMillis() - t0));
                    return new Routed<>(c, t, List.copyOf(attempts));
                })
                .onErrorResume(e -> {
                    attempts.add(new Attempt(t.provider().id(), t.provider().name(), t.upstreamModel(), describe(e), System.currentTimeMillis() - t0));
                    if (!failover(e)) return Mono.error(clientError(e));
                    log.warn("Provider {} failed ({}), trying next target", t.provider().id(), describe(e));
                    return tryComplete(org, targets, i + 1, body, attempts);
                });
    }

    public RoutedStream stream(OrgState org, List<Target> targets, ObjectNode body) {
        List<Attempt> attempts = Collections.synchronizedList(new ArrayList<>());
        Target[] servedBy = new Target[1];
        Flux<ObjectNode> flux = tryStream(org, targets, 0, body, attempts, servedBy);
        return new RoutedStream(flux, () -> servedBy[0], attempts);
    }

    private Flux<ObjectNode> tryStream(OrgState org, List<Target> targets, int i, ObjectNode body, List<Attempt> attempts, Target[] servedBy) {
        if (i >= targets.size()) return Flux.error(exhausted(attempts));
        Target t = targets.get(i);
        long t0 = System.currentTimeMillis();
        AtomicBoolean started = new AtomicBoolean(false);
        LlmProvider provider = providers.get(t.provider().kind());
        ProviderCall call = new ProviderCall(t.provider(), org.providerKeys().get(t.provider().id()), t.upstreamModel(), body);
        return Flux.defer(() -> provider.stream(call))
                .timeout(props.upstreamTimeout())
                .transformDeferred(CircuitBreakerOperator.of(breaker(org, t)))
                .doOnNext(c -> {
                    if (started.compareAndSet(false, true)) {
                        servedBy[0] = t;
                        attempts.add(new Attempt(t.provider().id(), t.provider().name(), t.upstreamModel(), "ok", System.currentTimeMillis() - t0));
                    }
                })
                .onErrorResume(e -> {
                    if (started.get()) return Flux.error(e);
                    attempts.add(new Attempt(t.provider().id(), t.provider().name(), t.upstreamModel(), describe(e), System.currentTimeMillis() - t0));
                    if (!failover(e)) return Flux.error(clientError(e));
                    return tryStream(org, targets, i + 1, body, attempts, servedBy);
                });
    }

    private CircuitBreaker breaker(OrgState org, Target t) {
        return breakers.circuitBreaker(org.config().orgId() + ":" + t.provider().id());
    }

    public Map<String, String> breakerStates() {
        Map<String, String> m = new java.util.TreeMap<>();
        breakers.getAllCircuitBreakers().forEach(b -> m.put(b.getName(), b.getState().name()));
        return m;
    }

    private static boolean failover(Throwable e) {
        if (e instanceof UpstreamException u) return u.retryable();
        return e instanceof TimeoutException || e instanceof CallNotPermittedException || e instanceof WebClientRequestException
                || e instanceof java.io.IOException;
    }

    private static Throwable clientError(Throwable e) {
        if (e instanceof UpstreamException u) {
            return new ProxyException(HttpStatus.BAD_REQUEST, "invalid_request_error", "upstream_rejected", u.getMessage());
        }
        return e;
    }

    private static ProxyException exhausted(List<Attempt> attempts) {
        return new ProxyException(HttpStatus.BAD_GATEWAY, "server_error", "all_providers_failed",
                "Every provider for this model failed: " + String.join("; ",
                        attempts.stream().map(a -> a.providerId() + " " + a.outcome()).toList()),
                Map.of("attempts", attempts), 0);
    }

    static String describe(Throwable e) {
        if (e instanceof CallNotPermittedException) return "circuit open (skipped)";
        if (e instanceof TimeoutException) return "timed out";
        if (e instanceof UpstreamException u) return "HTTP " + u.status() + (u.getMessage().contains("Simulated") ? " (simulated outage)" : "");
        if (e instanceof WebClientRequestException) return "unreachable";
        return e.getClass().getSimpleName();
    }
}
