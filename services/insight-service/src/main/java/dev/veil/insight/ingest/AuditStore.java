package dev.veil.insight.ingest;

import dev.veil.contracts.AuditEvent;
import dev.veil.insight.config.JsonCodec;
import dev.veil.insight.incidents.IncidentService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

/**
 * Stores audit events idempotently. Rollups and incidents are only updated for rows that were actually inserted,
 * so a redelivered Kafka batch can't double-count anything: effectively exactly-once on top of at-least-once.
 */
@Component
public class AuditStore {

    private static final List<String> SEVERITY_ORDER = List.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final Set<String> PROTECTIVE = Set.of("REDACT", "BLOCK", "ROUTE_LOCAL", "MASKED");

    private final JdbcClient jdbc;
    private final JsonCodec json;
    private final IncidentService incidents;

    public AuditStore(JdbcClient jdbc, JsonCodec json, IncidentService incidents) {
        this.jdbc = jdbc;
        this.json = json;
        this.incidents = incidents;
    }

    /** @return how many events were new */
    @Transactional
    public int store(List<AuditEvent> events) {
        int inserted = 0;
        for (AuditEvent e : events) {
            if (insert(e)) {
                inserted++;
                rollup(e);
                incidents.consider(e);
            }
        }
        return inserted;
    }

    private boolean insert(AuditEvent e) {
        List<String> types = e.findings().stream().map(AuditEvent.FindingSummary::type).distinct().toList();
        String top = e.findings().stream().map(AuditEvent.FindingSummary::severity)
                .max((a, b) -> Integer.compare(SEVERITY_ORDER.indexOf(a), SEVERITY_ORDER.indexOf(b))).orElse(null);
        return jdbc.sql("""
                        INSERT INTO requests (id, at, org_id, key_id, team_id, team_name, actor, source, kind, site, model, provider,
                          action, status, reasons, findings, finding_count, top_severity, types, redacted_prompt, prompt_chars,
                          tokens_in, tokens_out, cost_micros, latency_ms, detect_micros, upstream_ms, cache_hit, stream, policy_version)
                        VALUES (:id, :at, :org, :key, :team, :teamName, :actor, :source, :kind, :site, :model, :provider,
                          :action, :status, :reasons::jsonb, :findings::jsonb, :count, :top, :types, :prompt, :chars,
                          :in, :out, :cost, :latency, :detect, :upstream, :cache, :stream, :policy)
                        ON CONFLICT (id, at) DO NOTHING""")
                .param("id", e.id()).param("at", Timestamp.from(e.at())).param("org", e.orgId()).param("key", e.keyId())
                .param("team", e.teamId()).param("teamName", e.teamName()).param("actor", e.actor()).param("source", e.source())
                .param("kind", e.kind()).param("site", e.site()).param("model", e.model()).param("provider", e.provider())
                .param("action", e.action()).param("status", e.status()).param("reasons", json.write(e.reasons()))
                .param("findings", json.write(e.findings())).param("count", e.findings().size()).param("top", top)
                .param("types", types.toArray(String[]::new)).param("prompt", e.redactedPrompt()).param("chars", e.promptChars())
                .param("in", e.tokensIn()).param("out", e.tokensOut()).param("cost", e.costMicros()).param("latency", e.latencyMs())
                .param("detect", e.detectMicros()).param("upstream", e.upstreamMs()).param("cache", e.cacheHit())
                .param("stream", e.stream()).param("policy", e.policyVersion())
                .update() == 1;
    }

    private void rollup(AuditEvent e) {
        Timestamp minute = Timestamp.from(e.at().truncatedTo(ChronoUnit.MINUTES));
        String team = e.teamId() == null ? "-" : e.teamId();
        String model = e.model() == null ? "-" : e.model();
        boolean counted = !"VISIT".equals(e.kind());
        long protectedCount = e.findings().stream().filter(f -> PROTECTIVE.contains(f.action())).count();
        jdbc.sql("""
                        INSERT INTO usage_minute AS u (org_id, minute, team_id, source, model, requests, blocked, redacted, warned,
                          routed_local, cache_hits, errors, findings, protected, tokens_in, tokens_out, cost_micros, latency_sum_ms)
                        VALUES (:org, :minute, :team, :source, :model, :req, :blocked, :redacted, :warned, :local, :cache, :errors,
                          :findings, :protected, :in, :out, :cost, :latency)
                        ON CONFLICT (org_id, minute, team_id, source, model) DO UPDATE SET
                          requests = u.requests + excluded.requests, blocked = u.blocked + excluded.blocked,
                          redacted = u.redacted + excluded.redacted, warned = u.warned + excluded.warned,
                          routed_local = u.routed_local + excluded.routed_local, cache_hits = u.cache_hits + excluded.cache_hits,
                          errors = u.errors + excluded.errors, findings = u.findings + excluded.findings,
                          protected = u.protected + excluded.protected, tokens_in = u.tokens_in + excluded.tokens_in,
                          tokens_out = u.tokens_out + excluded.tokens_out, cost_micros = u.cost_micros + excluded.cost_micros,
                          latency_sum_ms = u.latency_sum_ms + excluded.latency_sum_ms""")
                .param("org", e.orgId()).param("minute", minute).param("team", team).param("source", e.source()).param("model", model)
                .param("req", counted ? 1 : 0).param("blocked", "BLOCK".equals(e.action()) ? 1 : 0)
                .param("redacted", "REDACT".equals(e.action()) ? 1 : 0).param("warned", "WARN".equals(e.action()) ? 1 : 0)
                .param("local", "ROUTE_LOCAL".equals(e.action()) ? 1 : 0).param("cache", e.cacheHit() ? 1 : 0)
                .param("errors", "UPSTREAM_ERROR".equals(e.status()) ? 1 : 0).param("findings", e.findings().size())
                .param("protected", protectedCount).param("in", e.tokensIn()).param("out", e.tokensOut())
                .param("cost", e.costMicros()).param("latency", counted ? e.latencyMs() : 0).update();
        for (AuditEvent.FindingSummary f : e.findings()) {
            jdbc.sql("""
                            INSERT INTO finding_minute AS f (org_id, minute, type, team_id, action, count) VALUES (?, ?, ?, ?, ?, 1)
                            ON CONFLICT (org_id, minute, type, team_id, action) DO UPDATE SET count = f.count + 1""")
                    .params(e.orgId(), minute, f.type(), team, f.action()).update();
        }
    }
}
