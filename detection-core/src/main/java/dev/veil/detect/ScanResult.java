package dev.veil.detect;

import java.util.List;
import java.util.Map;

/**
 * @param detectorMicros time spent per detector group, for the latency breakdown in the console
 * @param totalMicros    wall time of the whole scan
 */
public record ScanResult(List<Finding> findings, Map<String, Long> detectorMicros, long totalMicros) {

    public boolean isEmpty() {
        return findings.isEmpty();
    }

    public ScanResult plus(ScanResult other) {
        var all = new java.util.ArrayList<>(findings);
        all.addAll(other.findings);
        var micros = new java.util.LinkedHashMap<>(detectorMicros);
        other.detectorMicros.forEach((k, v) -> micros.merge(k, v, Long::sum));
        return new ScanResult(all, micros, totalMicros + other.totalMicros);
    }
}
