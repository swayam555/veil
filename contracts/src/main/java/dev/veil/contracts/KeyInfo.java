package dev.veil.contracts;

/**
 * What the proxy knows about a virtual key. The key itself is never stored, only its SHA-256.
 *
 * @param kind               {@code API} for applications, {@code EXTENSION} for browser extension enrollments
 * @param rpmLimit           requests per minute (0 = unlimited)
 * @param dailyBudgetMicros  spend cap per day in micro-dollars (0 = unlimited)
 */
public record KeyInfo(String keyId, String orgId, String teamId, String teamName, String name, String kind,
                      int rpmLimit, long dailyBudgetMicros, boolean revoked) {
}
