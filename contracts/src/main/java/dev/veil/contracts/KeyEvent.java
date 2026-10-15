package dev.veil.contracts;

/** A virtual key was revoked or changed; proxies evict it from their caches. */
public record KeyEvent(String orgId, String keyId, String keyHash, String change) {
}
