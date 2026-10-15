package dev.veil.insight.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param retentionDays how long raw request rows are kept (rollups and incidents are kept)
 */
@ConfigurationProperties("veil")
public record AppProperties(int retentionDays, Ai ai) {

    public record Ai(String provider, String apiKey, String baseUrl, String model, Duration timeout) {
    }
}
