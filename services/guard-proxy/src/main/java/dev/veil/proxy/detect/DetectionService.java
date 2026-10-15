package dev.veil.proxy.detect;

import dev.veil.detect.Finding;
import dev.veil.detect.ScanEngine;
import dev.veil.detect.ScanResult;
import dev.veil.policy.Policy;
import dev.veil.proxy.auth.ProxyException;
import dev.veil.proxy.config.ProxyProperties;
import dev.veil.proxy.state.OrgState;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Runs detection off the Netty event loop on a bounded pool (a bulkhead: a burst of huge prompts can't starve
 * request I/O), with a time budget. If the budget is exceeded the organisation's fail mode decides: CLOSED
 * refuses the request, OPEN falls back to the fast pattern detectors only.
 */
@Service
public class DetectionService {

    private static final Logger log = LoggerFactory.getLogger(DetectionService.class);

    private final Scheduler pool = Schedulers.newBoundedElastic(POOL,
            10_000, "detect");
    private static final int POOL = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
    /**
     * Above this many requests in detection (running or waiting for the pool), a new request would only wait until it
     * timed out, so it is decided by the fail mode at once instead. Without this, an overload makes every queued
     * request time out together (a convoy) and nobody gets served.
     */
    static final int SHED_IN_FLIGHT = POOL * 2;

    private final java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();

    private final ProxyProperties props;
    private final Timer timer;
    private final dev.veil.proxy.state.Embeddings embeddings;
    private final io.micrometer.core.instrument.Counter shed;

    public DetectionService(ProxyProperties props, MeterRegistry meters, dev.veil.proxy.state.Embeddings embeddings) {
        this.props = props;
        this.embeddings = embeddings;
        io.micrometer.core.instrument.Gauge.builder("veil.detection.in_flight", inFlight, java.util.concurrent.atomic.AtomicInteger::get).register(meters);
        this.shed = io.micrometer.core.instrument.Counter.builder("veil.detection.shed")
                .description("Requests decided without semantic detection because the embedding queue was full").register(meters);
        this.timer = Timer.builder("veil.detection").description("Detection time per request")
                .publishPercentiles(0.5, 0.95, 0.99).register(meters);
    }

    /** Findings per text, in the same order as the texts. */
    public record Scan(List<List<Finding>> perText, Map<String, Long> detectorMicros, long micros, boolean degraded) {
        public List<Finding> all() {
            List<Finding> out = new ArrayList<>();
            perText.forEach(out::addAll);
            return out;
        }
    }

    public Mono<Scan> scan(OrgState org, List<String> texts) {
        if (inFlight.get() >= SHED_IN_FLIGHT) {
            // Load shedding: waiting would only end in a timeout. Decide now, the same way a timeout would.
            shed.increment();
            return degrade(org, texts, "overloaded");
        }
        inFlight.incrementAndGet();
        return run(org.engine(), texts)
                .doFinally(signal -> inFlight.decrementAndGet())
                .timeout(props.detectionTimeout())
                .onErrorResume(TimeoutException.class, e -> degrade(org, texts, "timeout"));
    }

    /** Fail closed: refuse with 503 and Retry-After. Fail open: fast pattern detectors only, flagged as degraded. */
    private Mono<Scan> degrade(OrgState org, List<String> texts, String why) {
        if (org.config().policy().failMode() == Policy.FailMode.CLOSED) {
            return Mono.error(new ProxyException(HttpStatus.SERVICE_UNAVAILABLE, "server_error", "detection_" + why,
                    "Veil is too busy to finish checking this request, and your organisation's policy is to fail closed. Retry shortly.",
                    java.util.Map.of(), 1));
        }
        log.debug("Detection {}; failing open with pattern detectors only", why);
        return run(org.engineWithoutSemantic(), texts).map(s -> new Scan(s.perText(), s.detectorMicros(), s.micros(), true));
    }

    /** Pattern-only scan, used for scanning model output (fast, no embeddings). */
    public Mono<Scan> scanPatternsOnly(OrgState org, List<String> texts) {
        return run(org.engineWithoutSemantic(), texts);
    }

    private Mono<Scan> run(ScanEngine engine, List<String> texts) {
        return Mono.fromCallable(() -> {
            long t0 = System.nanoTime();
            List<List<Finding>> per = new ArrayList<>(texts.size());
            Map<String, Long> micros = new LinkedHashMap<>();
            for (String t : texts) {
                ScanResult r = engine.scan(t);
                per.add(r.findings());
                r.detectorMicros().forEach((k, v) -> micros.merge(k, v, Long::sum));
            }
            long elapsed = System.nanoTime() - t0;
            timer.record(java.time.Duration.ofNanos(elapsed));
            return new Scan(per, micros, elapsed / 1000, false);
        }).subscribeOn(pool);
    }
}
