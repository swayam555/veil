package dev.veil.insight.incidents;

import dev.veil.insight.config.Caller;
import dev.veil.insight.config.JsonCodec;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

@RestController
@RequestMapping("/api/insight/incidents")
public class IncidentsController {

    private static final Set<String> STATUSES = Set.of("OPEN", "ACKNOWLEDGED", "RESOLVED", "FALSE_POSITIVE");
    private static final String COLUMNS = """
            id, request_id AS "requestId", first_at AS "firstAt", last_at AS "lastAt", occurrences, severity, title,
            team_id AS "teamId", team_name AS "teamName", actor, key_id AS "keyId", source, site, types, action, status, assignee,
            notes, explanation, explained_by AS "explainedBy", cardinality(fingerprints) AS "distinctValues", updated_at AS "updatedAt"
            """;

    private final JdbcClient jdbc;
    private final IncidentExplainer explainer;
    private final JsonCodec json;

    public IncidentsController(JdbcClient jdbc, IncidentExplainer explainer, JsonCodec json) {
        this.jdbc = jdbc;
        this.explainer = explainer;
        this.json = json;
    }

    public record Update(String status, String assignee, String notes) {
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String status, @RequestParam(required = false) String severity,
                                    HttpServletRequest req) {
        String org = Caller.from(req).orgId();
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM incidents WHERE org_id = :org");
        Map<String, Object> p = new LinkedHashMap<>(Map.of("org", org));
        if ("active".equals(status)) sql.append(" AND status IN ('OPEN', 'ACKNOWLEDGED')");
        else if (status != null && STATUSES.contains(status)) {
            sql.append(" AND status = :status");
            p.put("status", status);
        }
        if (severity != null && !severity.isBlank()) {
            sql.append(" AND severity = :severity");
            p.put("severity", severity);
        }
        sql.append(" ORDER BY CASE status WHEN 'OPEN' THEN 0 WHEN 'ACKNOWLEDGED' THEN 1 ELSE 2 END, last_at DESC LIMIT 200");
        var spec = jdbc.sql(sql.toString());
        for (var e : p.entrySet()) spec = spec.param(e.getKey(), e.getValue());
        List<Map<String, Object>> items = spec.query().listOfRows().stream().map(IncidentsController::arrays).toList();
        Map<String, Object> counts = jdbc.sql("""
                        SELECT count(*) FILTER (WHERE status = 'OPEN') AS open, count(*) FILTER (WHERE status = 'ACKNOWLEDGED') AS acknowledged,
                               count(*) FILTER (WHERE status = 'RESOLVED') AS resolved, count(*) FILTER (WHERE status = 'FALSE_POSITIVE') AS "falsePositive",
                               count(*) FILTER (WHERE severity = 'CRITICAL' AND status IN ('OPEN','ACKNOWLEDGED')) AS critical
                        FROM incidents WHERE org_id = ?""").param(org).query().singleRow();
        return Map.of("items", items, "counts", counts);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable long id, HttpServletRequest req) {
        String org = Caller.from(req).orgId();
        Map<String, Object> incident = find(org, id);
        jdbc.sql("""
                        SELECT reasons::text AS reasons, findings::text AS findings, redacted_prompt AS "redactedPrompt", model, provider,
                               policy_version AS "policyVersion", latency_ms AS "latencyMs"
                        FROM requests WHERE org_id = ? AND id = ? ORDER BY at DESC LIMIT 1""")
                .params(org, incident.get("requestId")).query().listOfRows().stream().findFirst().ifPresent(r -> {
                    Map<String, Object> request = new LinkedHashMap<>(r);
                    request.put("reasons", json.read((String) r.get("reasons"), Object.class));
                    request.put("findings", json.read((String) r.get("findings"), Object.class));
                    incident.put("request", request);
                });
        return incident;
    }

    @PatchMapping("/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody Update body, HttpServletRequest req) {
        Caller c = Caller.from(req).requireAdmin();
        if (body.status() != null && !STATUSES.contains(body.status())) throw new IllegalArgumentException("Unknown status " + body.status());
        if (body.notes() != null && body.notes().length() > 4000) throw new IllegalArgumentException("Notes are limited to 4,000 characters");
        int n = jdbc.sql("""
                        UPDATE incidents SET status = coalesce(:status, status), assignee = coalesce(:assignee, assignee),
                          notes = coalesce(:notes, notes), updated_at = now() WHERE org_id = :org AND id = :id""")
                .param("status", body.status()).param("assignee", body.assignee()).param("notes", body.notes())
                .param("org", c.orgId()).param("id", id).update();
        if (n == 0) throw new NoSuchElementException("No incident " + id);
        return get(id, req);
    }

    /** Generates (or regenerates) the plain-language explanation. Only types and the redacted prompt reach the model. */
    @PostMapping("/{id}/explain")
    public Map<String, Object> explain(@PathVariable long id, HttpServletRequest req) {
        Caller c = Caller.from(req);
        Map<String, Object> i = get(id, req);
        @SuppressWarnings("unchecked") Map<String, Object> request = (Map<String, Object>) i.getOrDefault("request", Map.of());
        @SuppressWarnings("unchecked") List<String> reasons = request.get("reasons") instanceof List<?> l ? (List<String>) l : List.of();
        @SuppressWarnings("unchecked") List<String> types = (List<String>) i.get("types");
        var facts = new IncidentExplainer.Facts((String) i.get("title"), (String) i.get("severity"), (String) i.get("action"), types,
                (String) i.get("teamName"), (String) i.get("actor"), (String) i.get("source"), (String) i.get("site"),
                (String) request.get("model"), ((Number) i.get("occurrences")).intValue(), reasons, (String) request.get("redactedPrompt"));
        IncidentExplainer.Explanation e = explainer.explain(facts);
        jdbc.sql("UPDATE incidents SET explanation = ?, explained_by = ?, updated_at = now() WHERE org_id = ? AND id = ?")
                .params(e.text(), e.by(), c.orgId(), id).update();
        return get(id, req);
    }

    private Map<String, Object> find(String org, long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM incidents WHERE org_id = ? AND id = ?").params(org, id).query().listOfRows()
                .stream().findFirst().map(IncidentsController::arrays).orElseThrow(() -> new NoSuchElementException("No incident " + id));
    }

    static Map<String, Object> arrays(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>(row);
        m.replaceAll((k, v) -> {
            if (v instanceof java.sql.Array a) {
                try {
                    return Arrays.asList((Object[]) a.getArray());
                } catch (java.sql.SQLException e) {
                    return List.of();
                }
            }
            return v;
        });
        return m;
    }
}
