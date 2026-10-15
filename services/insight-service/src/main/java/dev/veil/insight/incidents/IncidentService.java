package dev.veil.insight.incidents;

import dev.veil.contracts.AuditEvent;
import dev.veil.detect.EntityType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Turns audit events into incidents worth a human's attention: anything blocked, any critical finding, prompt
 * injection and model output that had to be masked. Repeats from the same
 * person or key with the same data types within 30 minutes are folded into one incident with a count, so a
 * misbehaving script produces one incident, not a thousand.
 */
@Service
public class IncidentService {

    private static final Duration FOLD_WINDOW = Duration.ofMinutes(30);

    private final JdbcClient jdbc;

    public IncidentService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void consider(AuditEvent e) {
        Optional<String> severity = severity(e);
        if (severity.isEmpty()) return;
        Set<String> types = new TreeSet<>();
        Set<String> fingerprints = new LinkedHashSet<>();
        for (var f : e.findings()) {
            if (!"ALLOW".equals(f.action())) types.add(f.type());
            if (f.fingerprint() != null) fingerprints.add(f.fingerprint());
        }
        if (types.isEmpty()) e.findings().forEach(f -> types.add(f.type()));
        String who = e.actor() != null ? e.actor() : e.keyId();
        String dedupe = String.join("|", who == null ? "-" : who, String.join(",", types), e.action(), e.site() == null ? "-" : e.site());
        Timestamp at = Timestamp.from(e.at());
        int folded = jdbc.sql("""
                        UPDATE incidents SET occurrences = occurrences + 1, last_at = greatest(last_at, :at), updated_at = now(),
                          fingerprints = coalesce((SELECT array_agg(DISTINCT x) FROM unnest(fingerprints || :fps) x), ARRAY[]::text[]),
                          severity = CASE WHEN :sev = 'CRITICAL' THEN 'CRITICAL' ELSE severity END
                        WHERE id = (SELECT id FROM incidents WHERE org_id = :org AND dedupe_key = :dedupe
                                    AND status IN ('OPEN', 'ACKNOWLEDGED') AND last_at > :since ORDER BY last_at DESC LIMIT 1)""")
                .param("at", at).param("fps", fingerprints.toArray(String[]::new)).param("sev", severity.get())
                .param("org", e.orgId()).param("dedupe", dedupe).param("since", Timestamp.from(e.at().minus(FOLD_WINDOW)))
                .update();
        if (folded > 0) return;
        jdbc.sql("""
                        INSERT INTO incidents (org_id, request_id, first_at, last_at, severity, title, team_id, team_name, actor, key_id,
                          source, site, types, action, fingerprints, dedupe_key)
                        VALUES (:org, :req, :at, :at, :sev, :title, :team, :teamName, :actor, :key, :source, :site, :types, :action, :fps, :dedupe)""")
                .param("org", e.orgId()).param("req", e.id()).param("at", at).param("sev", severity.get()).param("title", title(e, types))
                .param("team", e.teamId()).param("teamName", e.teamName()).param("actor", e.actor()).param("key", e.keyId())
                .param("source", e.source()).param("site", e.site()).param("types", types.toArray(String[]::new))
                .param("action", e.action()).param("fps", fingerprints.toArray(String[]::new)).param("dedupe", dedupe).update();
    }

    static Optional<String> severity(AuditEvent e) {
        boolean critical = e.findings().stream().anyMatch(f -> "CRITICAL".equals(f.severity()) && !"ALLOW".equals(f.action()));
        boolean injection = e.findings().stream().anyMatch(f -> "PROMPT_INJECTION".equals(f.type()));
        boolean outputMasked = e.findings().stream().anyMatch(f -> "output".equals(f.location()));
        boolean blocked = "BLOCK".equals(e.action());
        // Merely flagged pastes are visible under Shadow AI; making each one an incident buries the real ones.
        if (critical) return Optional.of("CRITICAL");
        if (blocked || injection || outputMasked) return Optional.of("HIGH");
        return Optional.empty();
    }

    static String title(AuditEvent e, Set<String> types) {
        String team = e.teamName() == null ? "" : " (" + e.teamName() + ")";
        if (types.isEmpty()) {
            // Blocked for a reason other than data, e.g. a model that isn't on the allowlist.
            String reason = e.reasons().isEmpty() ? "Request blocked by policy" : e.reasons().getFirst();
            return capitalise(reason.length() > 90 ? reason.substring(0, 87) + "…" : reason) + team;
        }
        List<String> labels = types.stream().map(t -> {
            try {
                return EntityType.valueOf(t).label();
            } catch (IllegalArgumentException x) {
                return t;
            }
        }).toList();
        String what = labels.size() <= 2 ? String.join(" and ", labels) : labels.get(0) + ", " + labels.get(1) + " and " + (labels.size() - 2) + " more";
        String verb = switch (e.action()) {
            case "BLOCK" -> "blocked";
            case "ROUTE_LOCAL" -> "kept on-prem";
            case "REDACT" -> "redacted";
            default -> "flagged";
        };
        String where = "extension".equals(e.source()) && e.site() != null ? " on " + e.site()
                : e.model() != null ? " for " + e.model() : "";
        return capitalise(what) + " " + verb + where + team;
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
