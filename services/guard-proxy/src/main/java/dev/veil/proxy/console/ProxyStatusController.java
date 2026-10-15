package dev.veil.proxy.console;

import dev.veil.proxy.audit.AuditPublisher;
import dev.veil.proxy.auth.KeyResolver;
import dev.veil.proxy.cache.SemanticCache;
import dev.veil.proxy.llm.ProviderFaults;
import dev.veil.proxy.llm.ProviderRouter;
import dev.veil.proxy.state.ConfigStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Live state of this proxy instance: config versions, circuit breakers, caches, buffers, latency percentiles. */
@RestController
public class ProxyStatusController {

    private final ConfigStore configs;
    private final ProviderRouter router;
    private final ProviderFaults faults;
    private final SemanticCache cache;
    private final AuditPublisher audit;
    private final KeyResolver keys;
    private final MeterRegistry meters;

    public ProxyStatusController(ConfigStore configs, ProviderRouter router, ProviderFaults faults, SemanticCache cache,
                                 AuditPublisher audit, KeyResolver keys, MeterRegistry meters) {
        this.configs = configs;
        this.router = router;
        this.faults = faults;
        this.cache = cache;
        this.audit = audit;
        this.keys = keys;
        this.meters = meters;
    }

    @GetMapping("/api/proxy/status")
    public Map<String, Object> status(ServerWebExchange exchange) {
        ConsoleCaller c = ConsoleCaller.from(exchange.getRequest());
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("configVersion", configs.versions().get(c.orgId()));
        s.put("policyVersion", configs.get(c.orgId()).map(o -> o.config().policy().version()).orElse(null));
        Map<String, String> breakers = new LinkedHashMap<>();
        router.breakerStates().forEach((k, v) -> {
            if (k.startsWith(c.orgId() + ":")) breakers.put(k.substring(c.orgId().length() + 1), v);
        });
        s.put("circuitBreakers", breakers);
        s.put("faults", faults.all());
        s.put("semanticCache", cache.available());
        s.put("cacheHits", count("veil.cache", "result", "hit"));
        s.put("cacheMisses", count("veil.cache", "result", "miss"));
        s.put("auditBuffered", audit.buffered());
        s.put("cachedKeys", keys.localCacheSize());
        s.put("detection", percentiles("veil.detection"));
        s.put("embedding", percentiles("veil.embedding"));
        s.put("uptimeSeconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        return s;
    }

    /** Fault injection for the simulated providers: demonstrates failover and circuit breakers. */
    @PutMapping("/api/proxy/faults/{providerId}")
    public Map<String, ProviderFaults.Fault> fault(@PathVariable String providerId, @RequestBody ProviderFaults.Fault fault,
                                                    ServerWebExchange exchange) {
        ConsoleCaller.from(exchange.getRequest()).requireAdmin();
        faults.set(providerId, fault);
        return faults.all();
    }

    @GetMapping("/api/proxy/faults")
    public Map<String, ProviderFaults.Fault> faults(ServerWebExchange exchange) {
        ConsoleCaller.from(exchange.getRequest());
        return faults.all();
    }

    private double count(String name, String tag, String value) {
        var c = meters.find(name).tag(tag, value).counter();
        return c == null ? 0 : c.count();
    }

    private Map<String, Object> percentiles(String name) {
        Timer t = meters.find(name).timer();
        Map<String, Object> m = new LinkedHashMap<>();
        if (t == null) return m;
        m.put("count", t.count());
        m.put("meanMs", round(t.mean(TimeUnit.MILLISECONDS)));
        for (var p : t.takeSnapshot().percentileValues()) {
            m.put("p" + (int) Math.round(p.percentile() * 100), round(p.value(TimeUnit.MILLISECONDS)));
        }
        return m;
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
