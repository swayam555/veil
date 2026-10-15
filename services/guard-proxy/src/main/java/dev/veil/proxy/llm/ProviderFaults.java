package dev.veil.proxy.llm;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fault injection for the built-in Echo providers, so failover, circuit breakers and latency can be demonstrated
 * and tested without a real outage. Set from the console (Providers screen) or the chaos test script.
 */
@Component
public class ProviderFaults {

    public record Fault(double errorRate, int extraLatencyMs) {
    }

    private final Map<String, Fault> faults = new ConcurrentHashMap<>();

    public Fault get(String providerId) {
        return faults.getOrDefault(providerId, new Fault(0, 0));
    }

    public void set(String providerId, Fault fault) {
        if (fault.errorRate() <= 0 && fault.extraLatencyMs() <= 0) faults.remove(providerId);
        else faults.put(providerId, new Fault(Math.min(1, fault.errorRate()), Math.max(0, Math.min(30_000, fault.extraLatencyMs()))));
    }

    public Map<String, Fault> all() {
        return Map.copyOf(faults);
    }
}
