package dev.veil.insight.api;

import dev.veil.detect.EntityType;
import dev.veil.insight.config.Caller;
import dev.veil.insight.ingest.LiveFeed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/** Read APIs for the console: dashboards, the audit log, shadow AI, compliance evidence and the live feed. */
@RestController
@RequestMapping("/api/insight")
public class InsightController {

    private static final Set<String> PROTECTIVE = Set.of("REDACT", "BLOCK", "ROUTE_LOCAL", "MASKED");

    private final JdbcClient jdbc;
    private final LiveFeed live;
    private final dev.veil.insight.config.JsonCodec json;

    public InsightController(JdbcClient jdbc, LiveFeed live, dev.veil.insight.config.JsonCodec json) {
        this.jdbc = jdbc;
        this.live = live;
        this.json = json;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(@RequestParam(defaultValue = "24h") String range, HttpServletRequest req) {
        String org = Caller.from(req).orgId();
        Range r = Range.parse(range);
        Instant from = r.from();
        Instant prevFrom = from.minus(r.length());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("range", r.name());
        out.put("from", from);
        out.put("totals", totals(org, from, Instant.now().plusSeconds(60)));
        out.put("previous", totals(org, prevFrom, from));
        Map<String, Object> latency = jdbc.sql("""
                        SELECT coalesce(percentile_cont(0.5) WITHIN GROUP (ORDER BY latency_ms), 0) AS p50,
                               coalesce(percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms), 0) AS p95,
                               coalesce(percentile_cont(0.5) WITHIN GROUP (ORDER BY detect_micros), 0) AS "detectP50Micros",
                               coalesce(percentile_cont(0.95) WITHIN GROUP (ORDER BY detect_micros), 0) AS "detectP95Micros",
                               coalesce(percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms - upstream_ms), 0) AS "overheadP95Ms"
                        FROM requests WHERE org_id = ? AND at >= ? AND kind = 'CHAT' AND status = 'OK'""")
                .params(org, Timestamp.from(from)).query().singleRow();
        out.put("latency", latency);
        out.put("series", series(org, r));
        out.put("findingTypes", jdbc.sql("""
                        SELECT type, sum(count) AS count, sum(count) FILTER (WHERE action IN ('REDACT','BLOCK','ROUTE_LOCAL','MASKED')) AS protected
                        FROM finding_minute WHERE org_id = ? AND minute >= ? GROUP BY type ORDER BY count DESC""")
                .params(org, Timestamp.from(from)).query().listOfRows().stream().map(this::withLabel).toList());
        Map<String, String> names = teamNames(org);
        out.put("teams", jdbc.sql("""
                        SELECT team_id AS "teamId", sum(requests) AS requests, sum(blocked) AS blocked, sum(protected) AS protected,
                               sum(cost_micros) AS "costMicros", sum(tokens_in + tokens_out) AS tokens
                        FROM usage_minute WHERE org_id = ? AND minute >= ? GROUP BY team_id ORDER BY requests DESC""")
                .params(org, Timestamp.from(from)).query().listOfRows().stream()
                .map(m -> {
                    Map<String, Object> x = new LinkedHashMap<>(m);
                    x.put("team", names.getOrDefault((String) m.get("teamId"), (String) m.get("teamId")));
                    return x;
                }).toList());
        out.put("models", jdbc.sql("""
                        SELECT model, sum(requests) AS requests, sum(cost_micros) AS "costMicros", sum(cache_hits) AS "cacheHits",
                               sum(routed_local) AS "routedLocal"
                        FROM usage_minute WHERE org_id = ? AND minute >= ? AND source = 'api' GROUP BY model ORDER BY requests DESC""")
                .params(org, Timestamp.from(from)).query().listOfRows());
        out.put("sources", jdbc.sql("""
                        SELECT source, sum(requests) AS requests, sum(protected) AS protected FROM usage_minute
                        WHERE org_id = ? AND minute >= ? GROUP BY source ORDER BY requests DESC""")
                .params(org, Timestamp.from(from)).query().listOfRows());
        out.put("openIncidents", jdbc.sql("SELECT count(*) FROM incidents WHERE org_id = ? AND status IN ('OPEN','ACKNOWLEDGED')")
                .param(org).query(Long.class).single());
        return out;
    }

    private Map<String, Object> totals(String org, Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT coalesce(sum(requests), 0) AS requests, coalesce(sum(blocked), 0) AS blocked,
                               coalesce(sum(redacted), 0) AS redacted, coalesce(sum(warned), 0) AS warned,
                               coalesce(sum(routed_local), 0) AS "routedLocal", coalesce(sum(cache_hits), 0) AS "cacheHits",
                               coalesce(sum(errors), 0) AS errors, coalesce(sum(findings), 0) AS findings,
                               coalesce(sum(protected), 0) AS protected, coalesce(sum(tokens_in), 0) AS "tokensIn",
                               coalesce(sum(tokens_out), 0) AS "tokensOut", coalesce(sum(cost_micros), 0) AS "costMicros",
                               coalesce(sum(latency_sum_ms), 0) AS "latencySumMs"
                        FROM usage_minute WHERE org_id = ? AND minute >= ? AND minute < ?""")
                .params(org, Timestamp.from(from), Timestamp.from(to)).query().singleRow();
    }

    private List<Map<String, Object>> series(String org, Range r) {
        Instant from = r.from();
        Map<Instant, Map<String, Object>> rows = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT date_bin(CAST(? AS interval), minute, TIMESTAMPTZ '2000-01-01') AS bucket,
                               sum(requests) AS requests, sum(blocked) AS blocked, sum(redacted + routed_local) AS protected_requests,
                               sum(protected) AS protected, sum(warned) AS warned, sum(cost_micros) AS cost
                        FROM usage_minute WHERE org_id = ? AND minute >= ? GROUP BY bucket ORDER BY bucket""")
                .params(r.pgInterval(), org, Timestamp.from(from))
                .query((rs, n) -> rows.put(rs.getTimestamp("bucket").toInstant(), Map.of(
                        "requests", rs.getLong("requests"), "blocked", rs.getLong("blocked"),
                        "protectedRequests", rs.getLong("protected_requests"), "protected", rs.getLong("protected"),
                        "warned", rs.getLong("warned"), "costMicros", rs.getLong("cost")))).list();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Instant t = from; !t.isAfter(Instant.now()); t = t.plus(r.bucket())) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("t", t);
            Map<String, Object> v = rows.get(t);
            for (String k : List.of("requests", "blocked", "protectedRequests", "protected", "warned", "costMicros")) {
                point.put(k, v == null ? 0L : v.get(k));
            }
            out.add(point);
        }
        return out;
    }

    private Map<String, Object> withLabel(Map<String, Object> m) {
        Map<String, Object> x = new LinkedHashMap<>(m);
        try {
            EntityType t = EntityType.valueOf((String) m.get("type"));
            x.put("label", t.label());
            x.put("category", t.category().name());
            x.put("severity", t.severity().name());
        } catch (IllegalArgumentException e) {
            x.put("label", m.get("type"));
        }
        return x;
    }

    private Map<String, String> teamNames(String org) {
        Map<String, String> names = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT DISTINCT ON (team_id) team_id, team_name FROM requests
                        WHERE org_id = ? AND team_id IS NOT NULL AND at > now() - interval '30 days' ORDER BY team_id, at DESC""")
                .param(org).query((rs, n) -> names.put(rs.getString(1), rs.getString(2))).list();
        return names;
    }

    /** Keyset-paginated, filterable audit log. {@code cursor} is the last row's "at|id". */
    @GetMapping("/requests")
    public Map<String, Object> requests(@RequestParam(required = false) String q, @RequestParam(required = false) String action,
                                        @RequestParam(required = false) String team, @RequestParam(required = false) String source,
                                        @RequestParam(required = false) String type, @RequestParam(required = false) String status,
                                        @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int limit,
                                        HttpServletRequest req) {
        String org = Caller.from(req).orgId();
        int n = Math.max(1, Math.min(200, limit));
        StringBuilder sql = new StringBuilder("""
                SELECT id, at, team_id AS "teamId", team_name AS "teamName", actor, source, kind, site, model, provider, action, status,
                       types, finding_count AS "findingCount", top_severity AS "topSeverity", tokens_in AS "tokensIn",
                       tokens_out AS "tokensOut", cost_micros AS "costMicros", latency_ms AS "latencyMs", cache_hit AS "cacheHit",
                       left(redacted_prompt, 160) AS preview
                FROM requests WHERE org_id = :org AND at > now() - interval '90 days'""");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("org", org);
        if (q != null && !q.isBlank()) {
            sql.append(" AND (redacted_prompt ILIKE :q OR actor ILIKE :q OR model ILIKE :q OR id = :exact OR site ILIKE :q)");
            params.put("q", "%" + q.trim().replace("%", "\\%") + "%");
            params.put("exact", q.trim());
        }
        if (action != null && !action.isBlank()) {
            sql.append(" AND action = :action");
            params.put("action", action);
        }
        if (team != null && !team.isBlank()) {
            sql.append(" AND team_id = :team");
            params.put("team", team);
        }
        if (source != null && !source.isBlank()) {
            sql.append(" AND source = :source");
            params.put("source", source);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            params.put("status", status);
        }
        if (type != null && !type.isBlank()) {
            sql.append(" AND types @> ARRAY[CAST(:type AS text)]");
            params.put("type", type);
        }
        if (cursor != null && cursor.contains("|")) {
            String[] c = cursor.split("\\|", 2);
            sql.append(" AND (at, id) < (:cAt, :cId)");
            params.put("cAt", Timestamp.from(Instant.parse(c[0])));
            params.put("cId", c[1]);
        }
        sql.append(" ORDER BY at DESC, id DESC LIMIT ").append(n + 1);
        var spec = jdbc.sql(sql.toString());
        for (var e : params.entrySet()) spec = spec.param(e.getKey(), e.getValue());
        List<Map<String, Object>> rows = new ArrayList<>(spec.query().listOfRows().stream().map(this::arrays).toList());
        String next = null;
        if (rows.size() > n) {
            rows = rows.subList(0, n);
            Map<String, Object> last = rows.getLast();
            next = ((Timestamp) last.get("at")).toInstant() + "|" + last.get("id");
        }
        return Map.of("items", rows, "nextCursor", next == null ? "" : next);
    }

    @GetMapping("/requests/{id}")
    public Map<String, Object> request(@PathVariable String id, HttpServletRequest req) {
        String org = Caller.from(req).orgId();
        return jdbc.sql("""
                        SELECT id, at, key_id AS "keyId", team_id AS "teamId", team_name AS "teamName", actor, source, kind, site, model,
                               provider, action, status, reasons::text AS reasons, findings::text AS findings, redacted_prompt AS "redactedPrompt",
                               prompt_chars AS "promptChars", tokens_in AS "tokensIn", tokens_out AS "tokensOut", cost_micros AS "costMicros",
                               latency_ms AS "latencyMs", detect_micros AS "detectMicros", upstream_ms AS "upstreamMs",
                               cache_hit AS "cacheHit", stream, policy_version AS "policyVersion"
                        FROM requests WHERE org_id = ? AND id = ? ORDER BY at DESC LIMIT 1""")
                .params(org, id).query().listOfRows().stream().findFirst().map(this::arrays)
                .map(m -> {
                    m.put("reasons", json.read((String) m.get("reasons"), Object.class));
                    m.put("findings", json.read((String) m.get("findings"), Object.class));
                    return m;
                })
                .orElseThrow(() -> new NoSuchElementException("No request " + id));
    }

    @GetMapping("/shadow-ai")
    public Map<String, Object> shadowAi(@RequestParam(defaultValue = "7d") String range, HttpServletRequest req) {
        String org = Caller.from(req).orgId();
        Timestamp from = Timestamp.from(Range.parse(range).from());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totals", jdbc.sql("""
                        SELECT count(*) FILTER (WHERE kind = 'VISIT') AS visits, count(*) FILTER (WHERE kind <> 'VISIT') AS pastes,
                               count(*) FILTER (WHERE kind <> 'VISIT' AND action <> 'ALLOW') AS flagged,
                               count(*) FILTER (WHERE action = 'BLOCK') AS blocked, count(DISTINCT actor) AS users,
                               count(DISTINCT site) AS sites
                        FROM requests WHERE org_id = ? AND source = 'extension' AND at >= ?""")
                .params(org, from).query().singleRow());
        out.put("sites", jdbc.sql("""
                        SELECT site, count(*) FILTER (WHERE kind = 'VISIT') AS visits, count(*) FILTER (WHERE kind <> 'VISIT') AS pastes,
                               count(*) FILTER (WHERE kind <> 'VISIT' AND action <> 'ALLOW') AS flagged,
                               count(*) FILTER (WHERE action = 'BLOCK') AS blocked, count(DISTINCT actor) AS users, max(at) AS "lastSeen"
                        FROM requests WHERE org_id = ? AND source = 'extension' AND at >= ? AND site IS NOT NULL
                        GROUP BY site ORDER BY count(*) DESC""")
                .params(org, from).query().listOfRows());
        out.put("teams", jdbc.sql("""
                        SELECT team_name AS team, count(*) FILTER (WHERE kind = 'VISIT') AS visits, count(*) FILTER (WHERE kind <> 'VISIT') AS pastes,
                               count(*) FILTER (WHERE kind <> 'VISIT' AND action <> 'ALLOW') AS flagged, count(DISTINCT actor) AS users
                        FROM requests WHERE org_id = ? AND source = 'extension' AND at >= ? GROUP BY team_name ORDER BY pastes DESC""")
                .params(org, from).query().listOfRows());
        out.put("risky", jdbc.sql("""
                        SELECT id, at, actor, team_name AS team, site, kind, action, types FROM requests
                        WHERE org_id = ? AND source = 'extension' AND at >= ? AND kind <> 'VISIT' AND action <> 'ALLOW'
                        ORDER BY at DESC LIMIT 25""")
                .params(org, from).query().listOfRows().stream().map(this::arrays).toList());
        return out;
    }

    /** Evidence for auditors: what was protected, under which regulation, and how the audit log itself is kept safe. */
    @GetMapping("/compliance")
    public Map<String, Object> compliance(@RequestParam(defaultValue = "30d") String range, HttpServletRequest req) {
        String org = Caller.from(req).orgId();
        Range r = Range.parse(range);
        Timestamp from = Timestamp.from(r.from());
        Map<String, long[]> byType = new LinkedHashMap<>();
        jdbc.sql("SELECT type, action, sum(count) FROM finding_minute WHERE org_id = ? AND minute >= ? GROUP BY type, action")
                .params(org, from).query((rs, n) -> {
                    long[] c = byType.computeIfAbsent(rs.getString(1), k -> new long[2]);
                    c[0] += rs.getLong(3);
                    if (PROTECTIVE.contains(rs.getString(2))) c[1] += rs.getLong(3);
                    return null;
                }).list();
        Map<String, Map<String, Object>> regs = new LinkedHashMap<>();
        byType.forEach((type, c) -> {
            EntityType t;
            try {
                t = EntityType.valueOf(type);
            } catch (IllegalArgumentException e) {
                return;
            }
            for (String reg : t.regulations()) {
                Map<String, Object> m = regs.computeIfAbsent(reg, k -> {
                    Map<String, Object> x = new LinkedHashMap<>();
                    x.put("regulation", k);
                    x.put("types", new LinkedHashSet<String>());
                    x.put("detected", 0L);
                    x.put("protected", 0L);
                    return x;
                });
                @SuppressWarnings("unchecked") Set<String> types = (Set<String>) m.get("types");
                types.add(t.label());
                m.put("detected", (Long) m.get("detected") + c[0]);
                m.put("protected", (Long) m.get("protected") + c[1]);
            }
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("range", r.name());
        out.put("regulations", regs.values().stream().sorted((a, b) -> Long.compare((Long) b.get("detected"), (Long) a.get("detected"))).toList());
        out.put("totals", totals(org, r.from(), Instant.now().plusSeconds(60)));
        out.put("incidents", jdbc.sql("""
                        SELECT status, count(*) AS count FROM incidents WHERE org_id = ? AND first_at >= ? GROUP BY status""")
                .params(org, from).query().listOfRows());
        out.put("policyVersions", jdbc.sql("SELECT DISTINCT policy_version FROM requests WHERE org_id = ? AND at >= ? ORDER BY 1")
                .params(org, from).query(Integer.class).list());
        out.put("controls", List.of(
                "Sensitive values are never stored: the audit log keeps data types, offsets and salted HMAC fingerprints only.",
                "Prompts are stored only in redacted form, with every detected value replaced by a placeholder.",
                "Redaction is reversible only inside the request that created it; the mapping lives in memory and is discarded.",
                "Every policy change is versioned with author and time, and requests record the policy version that decided them.",
                "Provider API keys are encrypted at rest with AES-256-GCM and never returned by any API.",
                "Raw request rows are partitioned by day and expire automatically after the retention period."));
        return out;
    }

    /** CSV of the audit log (no sensitive values: it never contains any). */
    @GetMapping(value = "/export.csv", produces = "text/csv")
    public void export(@RequestParam(defaultValue = "7d") String range, HttpServletRequest req, HttpServletResponse res) throws IOException {
        String org = Caller.from(req).orgId();
        Timestamp from = Timestamp.from(Range.parse(range).from());
        res.setContentType("text/csv");
        res.setHeader("Content-Disposition", "attachment; filename=\"veil-audit-" + range + ".csv\"");
        PrintWriter w = res.getWriter();
        w.println("id,at,team,actor,source,kind,site,model,provider,action,status,types,tokens_in,tokens_out,cost_usd,latency_ms,cache_hit,policy_version");
        jdbc.sql("""
                        SELECT id, at, team_name, actor, source, kind, site, model, provider, action, status, array_to_string(types, ' '),
                               tokens_in, tokens_out, cost_micros, latency_ms, cache_hit, policy_version
                        FROM requests WHERE org_id = ? AND at >= ? ORDER BY at DESC LIMIT 100000""")
                .params(org, from).query((rs, n) -> {
                    w.println(String.join(",", csv(rs.getString(1)), rs.getTimestamp(2).toInstant().toString(), csv(rs.getString(3)),
                            csv(rs.getString(4)), csv(rs.getString(5)), csv(rs.getString(6)), csv(rs.getString(7)), csv(rs.getString(8)),
                            csv(rs.getString(9)), csv(rs.getString(10)), csv(rs.getString(11)), csv(rs.getString(12)),
                            String.valueOf(rs.getInt(13)), String.valueOf(rs.getInt(14)), String.format("%.6f", rs.getLong(15) / 1e6),
                            String.valueOf(rs.getLong(16)), String.valueOf(rs.getBoolean(17)), String.valueOf(rs.getInt(18))));
                    return null;
                }).list();
        w.flush();
    }

    @GetMapping(value = "/live", produces = "text/event-stream")
    public SseEmitter live(HttpServletRequest req) {
        return live.subscribe(Caller.from(req).orgId());
    }

    private static String csv(String s) {
        if (s == null) return "";
        // Neutralise spreadsheet formulas and quote anything with separators.
        String v = s.matches("^[=+\\-@].*") ? "'" + s : s;
        return v.contains(",") || v.contains("\"") || v.contains("\n") ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    private Map<String, Object> arrays(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>(row);
        m.replaceAll((k, v) -> {
            if (v instanceof java.sql.Array a) {
                try {
                    return Arrays.asList((Object[]) a.getArray());
                } catch (java.sql.SQLException e) {
                    return List.of();
                }
            }
            if (v instanceof org.postgresql.util.PGobject p) return p.getValue();
            return v;
        });
        return m;
    }
}
