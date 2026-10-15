package dev.veil.control.keys;

import dev.veil.contracts.KeyInfo;
import dev.veil.control.config.Caller;
import dev.veil.control.config.Crypto;
import dev.veil.control.publish.AdminAudit;
import dev.veil.control.publish.ConfigPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * Virtual keys: what applications and browser extensions use instead of raw provider keys. Each belongs to a
 * team, has its own rate limit and budget, and can be revoked without touching the provider account.
 */
@Service
public class KeyService {

    private final JdbcClient jdbc;
    private final ConfigPublisher publisher;
    private final AdminAudit audit;

    public KeyService(JdbcClient jdbc, ConfigPublisher publisher, AdminAudit audit) {
        this.jdbc = jdbc;
        this.publisher = publisher;
        this.audit = audit;
    }

    public record Created(KeyView key, String secret) {
    }

    public record KeyView(String id, String name, String teamId, String teamName, String kind, String prefix, String last4,
                          int rpmLimit, long dailyBudgetMicros, String createdBy, Instant createdAt, Instant revokedAt) {
    }

    @Transactional
    public Created create(Caller c, String name, String teamId, String kind, int rpmLimit, long dailyBudgetMicros) {
        return createWithSecret(c, name, teamId, kind, rpmLimit, dailyBudgetMicros, null);
    }

    /** {@code fixedSecret} is only used by the demo seeder so the traffic simulator knows its keys. */
    @Transactional
    public Created createWithSecret(Caller c, String name, String teamId, String kind, int rpmLimit, long dailyBudgetMicros,
                                    String fixedSecret) {
        if (!"API".equals(kind) && !"EXTENSION".equals(kind)) throw new IllegalArgumentException("kind must be API or EXTENSION");
        boolean teamExists = jdbc.sql("SELECT count(*) FROM teams WHERE org_id = ? AND id = ?").params(c.orgId(), teamId)
                .query(Integer.class).single() > 0;
        if (!teamExists) throw new IllegalArgumentException("Unknown team " + teamId);
        String prefix = "EXTENSION".equals(kind) ? "vk_ext_" : "vk_live_";
        String secret = fixedSecret != null ? fixedSecret : prefix + Crypto.randomToken(24);
        String id = "key_" + UUID.randomUUID().toString().substring(0, 12);
        jdbc.sql("""
                        INSERT INTO virtual_keys (id, org_id, team_id, name, kind, key_hash, prefix, last4, rpm_limit, daily_budget_micros, created_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(id, c.orgId(), teamId, name, kind, Crypto.sha256(secret), prefix, secret.substring(secret.length() - 4),
                        rpmLimit, dailyBudgetMicros, c.name() == null ? c.userId() : c.name()).update();
        audit.record(c, "key.create", id, name + " (" + teamId + ")");
        return new Created(get(c.orgId(), id), secret);
    }

    @Transactional
    public KeyView revoke(Caller c, String id) {
        String hash = jdbc.sql("UPDATE virtual_keys SET revoked_at = now() WHERE org_id = ? AND id = ? AND revoked_at IS NULL RETURNING key_hash")
                .params(c.orgId(), id).query(String.class).optional()
                .orElseThrow(() -> new NoSuchElementException("No active key " + id));
        audit.record(c, "key.revoke", id, null);
        publisher.keyChanged(c.orgId(), id, hash, "REVOKED");
        return get(c.orgId(), id);
    }

    @Transactional
    public KeyView updateLimits(Caller c, String id, int rpmLimit, long dailyBudgetMicros) {
        String hash = jdbc.sql("UPDATE virtual_keys SET rpm_limit = ?, daily_budget_micros = ? WHERE org_id = ? AND id = ? RETURNING key_hash")
                .params(rpmLimit, dailyBudgetMicros, c.orgId(), id).query(String.class).optional()
                .orElseThrow(() -> new NoSuchElementException("No key " + id));
        audit.record(c, "key.limits", id, rpmLimit + " rpm, " + dailyBudgetMicros + " micro-USD/day");
        publisher.keyChanged(c.orgId(), id, hash, "UPDATED");
        return get(c.orgId(), id);
    }

    public List<KeyView> list(String orgId) {
        return jdbc.sql(SELECT + " WHERE k.org_id = ? ORDER BY k.revoked_at IS NOT NULL, k.created_at DESC").param(orgId).query(KeyView.class).list();
    }

    public KeyView get(String orgId, String id) {
        return jdbc.sql(SELECT + " WHERE k.org_id = ? AND k.id = ?").params(orgId, id).query(KeyView.class).optional()
                .orElseThrow(() -> new NoSuchElementException("No key " + id));
    }

    public Optional<KeyInfo> byHash(String hash) {
        return jdbc.sql("""
                        SELECT k.id, k.org_id, k.team_id, t.name, k.name, k.kind, k.rpm_limit, k.daily_budget_micros, k.revoked_at IS NOT NULL
                        FROM virtual_keys k JOIN teams t ON t.org_id = k.org_id AND t.id = k.team_id WHERE k.key_hash = ?""")
                .param(hash).query((rs, n) -> new KeyInfo(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getInt(7), rs.getLong(8), rs.getBoolean(9))).optional();
    }

    private static final String SELECT = """
            SELECT k.id, k.name, k.team_id, t.name AS team_name, k.kind, k.prefix, k.last4, k.rpm_limit, k.daily_budget_micros,
                   k.created_by, k.created_at, k.revoked_at
            FROM virtual_keys k JOIN teams t ON t.org_id = k.org_id AND t.id = k.team_id""";
}
