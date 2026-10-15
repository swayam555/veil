package dev.veil.proxy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param masterKey          base64 AES-256 key shared with the control plane, decrypts provider API keys
 * @param controlPlaneUrl    for cold start and key lookups on a cache miss
 * @param detectionTimeout   detection budget per request; on timeout the policy's fail mode applies
 * @param upstreamTimeout    per provider attempt
 * @param semanticCache      cosine-distance threshold for a semantic cache hit (0 = identical)
 * @param semanticDetection  run the embedding-based detectors (confidential topics, paraphrased injections)
 * @param maxBodyChars       requests with more text than this are rejected (cost and abuse protection)
 */
@ConfigurationProperties("veil")
public record ProxyProperties(String masterKey, String controlPlaneUrl, Duration detectionTimeout, Duration upstreamTimeout,
                              double semanticCacheDistance, Duration semanticCacheTtl, boolean semanticDetection,
                              int maxBodyChars, String instanceId) {
}
