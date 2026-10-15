package dev.veil.contracts;

import java.time.Instant;
import java.util.List;

/**
 * One request seen by Veil. It records what kind of data was found, where, and what was done, but never the
 * sensitive values themselves: each value is reduced to a salted fingerprint, so repeated leaks of the same
 * Aadhaar number can be correlated without storing it.
 *
 * @param source         {@code api}, {@code scan} or {@code extension}
 * @param kind           CHAT, SCAN, PASTE, SUBMIT or VISIT (extension saw an AI site)
 * @param status         OK, BLOCKED, RATE_LIMITED, BUDGET_EXCEEDED, UPSTREAM_ERROR, INVALID
 * @param redactedPrompt the prompt as the model saw it (placeholders instead of values), if the policy keeps it
 * @param costMicros     estimated provider cost in micro-dollars
 */
public record AuditEvent(
        String id,
        String orgId,
        String keyId,
        String teamId,
        String teamName,
        String actor,
        String source,
        String kind,
        String site,
        String model,
        String provider,
        String action,
        String status,
        List<String> reasons,
        List<FindingSummary> findings,
        String redactedPrompt,
        int promptChars,
        int tokensIn,
        int tokensOut,
        long costMicros,
        long latencyMs,
        long detectMicros,
        long upstreamMs,
        boolean cacheHit,
        boolean stream,
        int policyVersion,
        Instant at) {

    /**
     * @param location    {@code input} or {@code output} (found in the model's answer)
     * @param fingerprint first 12 hex characters of HMAC-SHA256(org salt, normalised value)
     */
    public record FindingSummary(String type, String category, String severity, String detail, double confidence,
                                 int start, int end, String fingerprint, String action, String location) {
    }
}
