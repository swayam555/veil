package dev.veil.control.policy;

import dev.veil.control.config.Caller;
import dev.veil.control.config.JsonCodec;
import dev.veil.control.publish.AdminAudit;
import dev.veil.control.publish.ConfigPublisher;
import dev.veil.policy.Policy;
import dev.veil.policy.Rule;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

/** Policies are immutable versions. Publishing writes a new version and pushes it to every proxy. */
@Service
public class PolicyService {

    private final JdbcClient jdbc;
    private final JsonCodec json;
    private final ConfigPublisher publisher;
    private final AdminAudit audit;

    public PolicyService(JdbcClient jdbc, JsonCodec json, ConfigPublisher publisher, AdminAudit audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.publisher = publisher;
        this.audit = audit;
    }

    public record Version(int version, String note, String createdBy, Instant createdAt, boolean active, int rules) {
    }

    public Policy active(String orgId) {
        return jdbc.sql("""
                        SELECT p.policy::text FROM policy_versions p JOIN orgs o ON o.id = p.org_id AND p.version = o.active_policy_version
                        WHERE o.id = ?""").param(orgId).query(String.class).optional()
                .map(s -> json.read(s, Policy.class)).orElse(Policy.allowAll());
    }

    public Policy version(String orgId, int version) {
        return jdbc.sql("SELECT policy::text FROM policy_versions WHERE org_id = ? AND version = ?").params(orgId, version)
                .query(String.class).optional().map(s -> json.read(s, Policy.class))
                .orElseThrow(() -> new NoSuchElementException("No policy version " + version));
    }

    public List<Version> versions(String orgId) {
        return jdbc.sql("""
                        SELECT p.version, p.note, p.created_by, p.created_at, p.version = o.active_policy_version AS active,
                               jsonb_array_length(p.policy -> 'rules') AS rules
                        FROM policy_versions p JOIN orgs o ON o.id = p.org_id WHERE p.org_id = ? ORDER BY p.version DESC""")
                .param(orgId).query(Version.class).list();
    }

    @Transactional
    public Policy publish(Caller c, Policy draft, String note) {
        validate(draft);
        int next = jdbc.sql("SELECT coalesce(max(version), 0) + 1 FROM policy_versions WHERE org_id = ?")
                .param(c.orgId()).query(Integer.class).single();
        Policy stamped = new Policy(next, draft.rules(), draft.allowedModels(), draft.maxOutputTokens(), draft.failMode(),
                draft.semanticCache(), draft.storeRedactedPrompts(), draft.outputScanning());
        jdbc.sql("INSERT INTO policy_versions (org_id, version, policy, note, created_by) VALUES (?, ?, ?::jsonb, ?, ?)")
                .params(c.orgId(), next, json.write(stamped), note, c.name() == null ? c.userId() : c.name()).update();
        jdbc.sql("UPDATE orgs SET active_policy_version = ? WHERE id = ?").params(next, c.orgId()).update();
        audit.record(c, "policy.publish", "v" + next, note);
        publisher.publish(c.orgId());
        return stamped;
    }

    /** Re-activates an old version as a new version (history is never rewritten). */
    @Transactional
    public Policy rollback(Caller c, int version) {
        return publish(c, version(c.orgId(), version), "Rollback to v" + version);
    }

    static void validate(Policy p) {
        if (p.rules().size() > 200) throw new IllegalArgumentException("A policy can have at most 200 rules");
        if (p.maxOutputTokens() < 0) throw new IllegalArgumentException("maxOutputTokens must be >= 0");
        Set<String> ids = new HashSet<>();
        for (Rule r : p.rules()) {
            if (r.id() == null || r.id().isBlank()) throw new IllegalArgumentException("Every rule needs an id");
            if (!ids.add(r.id())) throw new IllegalArgumentException("Duplicate rule id " + r.id());
            if (r.name() == null || r.name().isBlank()) throw new IllegalArgumentException("Rule " + r.id() + " needs a name");
            for (String s : r.sources()) {
                if (!s.equals("api") && !s.equals("extension")) throw new IllegalArgumentException("Unknown source " + s);
            }
        }
    }
}
