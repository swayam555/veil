package dev.veil.insight.api;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** A dashboard time range and the bucket width that gives about 60-90 points on a chart. */
public record Range(String name, Duration length, Duration bucket) {

    public static Range parse(String s) {
        return switch (s == null ? "24h" : s) {
            case "1h" -> new Range("1h", Duration.ofHours(1), Duration.ofMinutes(1));
            case "6h" -> new Range("6h", Duration.ofHours(6), Duration.ofMinutes(5));
            case "24h" -> new Range("24h", Duration.ofHours(24), Duration.ofMinutes(15));
            case "7d" -> new Range("7d", Duration.ofDays(7), Duration.ofHours(2));
            case "30d" -> new Range("30d", Duration.ofDays(30), Duration.ofHours(12));
            default -> throw new IllegalArgumentException("range must be 1h, 6h, 24h, 7d or 30d");
        };
    }

    /** Start of the range, aligned to a bucket boundary so charts don't jitter. */
    public Instant from() {
        Instant now = Instant.now();
        long b = bucket.toSeconds();
        long aligned = (now.getEpochSecond() / b) * b;
        return Instant.ofEpochSecond(aligned).minus(length).plus(bucket).truncatedTo(ChronoUnit.SECONDS);
    }

    public String pgInterval() {
        return bucket.toSeconds() + " seconds";
    }
}
