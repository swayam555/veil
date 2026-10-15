package dev.veil.contracts;

import dev.veil.policy.Policy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Everything a proxy needs to enforce an organisation's rules, pushed as one snapshot whenever anything changes.
 * Provider API keys travel encrypted (AES-GCM with the shared master key), never in plain text.
 *
 * @param version          increases on every change; proxies ignore older snapshots
 * @param teamDailyBudgets team id to daily budget in micro-dollars (0 = unlimited)
 * @param fingerprintSalt  per-organisation salt for value fingerprints in the audit log
 */
public record OrgConfig(
        String orgId,
        String orgName,
        long version,
        Policy policy,
        List<DictionaryTerm> dictionary,
        List<TopicExample> topics,
        List<ProviderConfig> providers,
        List<RouteConfig> routes,
        Map<String, Long> teamDailyBudgets,
        String fingerprintSalt,
        Instant updatedAt) {

    public record DictionaryTerm(String term, String label) {
    }

    /** An organisation-specific example sentence for the semantic detector. */
    public record TopicExample(String label, String description, String example) {
    }

    public enum ProviderKind { ECHO, OPENAI_COMPATIBLE, ANTHROPIC }

    /**
     * @param local true for models running on the organisation's own infrastructure (targets of ROUTE_LOCAL)
     */
    public record ProviderConfig(String id, String name, ProviderKind kind, String baseUrl, String apiKeyCiphertext,
                                 boolean local, boolean enabled) {
    }

    /** A model name clients can ask for, and where it actually goes (first healthy target wins). */
    public record RouteConfig(String model, List<Target> targets, double inputUsdPerMTok, double outputUsdPerMTok) {
        public record Target(String providerId, String upstreamModel) {
        }
    }

    public RouteConfig route(String model) {
        return routes.stream().filter(r -> r.model().equals(model)).findFirst().orElse(null);
    }

    public ProviderConfig provider(String id) {
        return providers.stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
    }
}
