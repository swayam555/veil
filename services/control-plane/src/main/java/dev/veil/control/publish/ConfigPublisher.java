package dev.veil.control.publish;

import dev.veil.contracts.KeyEvent;
import dev.veil.contracts.OrgConfig;
import dev.veil.contracts.Topics;
import dev.veil.control.config.JsonCodec;
import dev.veil.policy.Policy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds an organisation's full configuration snapshot and queues it for Kafka. Called inside the transaction
 * that changed anything, so the snapshot and the change commit (or roll back) together.
 */
@Component
public class ConfigPublisher {

    private final JdbcClient jdbc;
    private final JsonCodec json;
    private final Outbox outbox;

    public ConfigPublisher(JdbcClient jdbc, JsonCodec json, Outbox outbox) {
        this.jdbc = jdbc;
        this.json = json;
        this.outbox = outbox;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public OrgConfig publish(String orgId) {
        long version = jdbc.sql("UPDATE orgs SET config_version = config_version + 1 WHERE id = ? RETURNING config_version")
                .param(orgId).query(Long.class).single();
        OrgConfig config = build(orgId, version);
        outbox.add(Topics.CONFIG, orgId, config);
        return config;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void keyChanged(String orgId, String keyId, String keyHash, String change) {
        outbox.add(Topics.KEYS, orgId, new KeyEvent(orgId, keyId, keyHash, change));
    }

    /** The current snapshot without bumping the version (for proxies that start cold). */
    public OrgConfig current(String orgId) {
        long version = jdbc.sql("SELECT config_version FROM orgs WHERE id = ?").param(orgId).query(Long.class).single();
        return build(orgId, version);
    }

    private OrgConfig build(String orgId, long version) {
        var org = jdbc.sql("SELECT name, active_policy_version, fingerprint_salt FROM orgs WHERE id = ?").param(orgId)
                .query((rs, n) -> new String[]{rs.getString(1), String.valueOf(rs.getInt(2)), rs.getString(3)}).single();
        Policy policy = jdbc.sql("SELECT policy::text FROM policy_versions WHERE org_id = ? AND version = ?")
                .params(orgId, Integer.parseInt(org[1])).query(String.class).optional()
                .map(p -> json.read(p, Policy.class)).orElse(Policy.allowAll());
        List<OrgConfig.DictionaryTerm> dictionary = jdbc.sql("SELECT term, label FROM dictionary_terms WHERE org_id = ? ORDER BY id")
                .param(orgId).query(OrgConfig.DictionaryTerm.class).list();
        List<OrgConfig.TopicExample> topics = jdbc.sql("SELECT label, description, example FROM topic_examples WHERE org_id = ? ORDER BY id")
                .param(orgId).query(OrgConfig.TopicExample.class).list();
        List<OrgConfig.ProviderConfig> providers = jdbc.sql("""
                        SELECT id, name, kind, base_url, api_key_ciphertext, local, enabled FROM providers WHERE org_id = ? ORDER BY id""")
                .param(orgId).query((rs, n) -> new OrgConfig.ProviderConfig(rs.getString(1), rs.getString(2),
                        OrgConfig.ProviderKind.valueOf(rs.getString(3)), rs.getString(4), rs.getString(5),
                        rs.getBoolean(6), rs.getBoolean(7))).list();
        List<OrgConfig.RouteConfig> routes = jdbc.sql("""
                        SELECT model, targets::text, input_usd_per_mtok, output_usd_per_mtok FROM routes WHERE org_id = ? ORDER BY model""")
                .param(orgId).query((rs, n) -> new OrgConfig.RouteConfig(rs.getString(1),
                        json.read(rs.getString(2), new TypeReference<List<OrgConfig.RouteConfig.Target>>() {
                        }), rs.getDouble(3), rs.getDouble(4))).list();
        Map<String, Long> budgets = new LinkedHashMap<>();
        jdbc.sql("SELECT id, daily_budget_micros FROM teams WHERE org_id = ? ORDER BY id").param(orgId)
                .query((rs, n) -> budgets.put(rs.getString(1), rs.getLong(2))).list();
        return new OrgConfig(orgId, org[0], version, policy, dictionary, topics, providers, routes, budgets, org[2], Instant.now());
    }
}
