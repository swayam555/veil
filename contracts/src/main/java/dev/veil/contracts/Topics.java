package dev.veil.contracts;

/** Kafka topics. All are keyed by organisation id, so one organisation's events stay in order. */
public final class Topics {
    /** Full configuration snapshot per organisation (compacted). Control plane to every proxy instance. */
    public static final String CONFIG = "veil.config";
    /** Virtual key changes (revoked, limits changed), so proxies drop cached copies at once. */
    public static final String KEYS = "veil.keys";
    /** One event per request handled by the proxy, the Scan API or the browser extension. */
    public static final String AUDIT = "veil.audit";
    /** Audit events that could not be stored after retries. */
    public static final String AUDIT_DLQ = "veil.audit.dlq";

    private Topics() {
    }
}
