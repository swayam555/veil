package dev.veil.control.org;

import dev.veil.control.config.Caller;
import dev.veil.control.publish.AdminAudit;
import dev.veil.control.publish.ConfigPublisher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/teams")
public class TeamsController {

    private final JdbcClient jdbc;
    private final ConfigPublisher publisher;
    private final AdminAudit audit;

    public TeamsController(JdbcClient jdbc, ConfigPublisher publisher, AdminAudit audit) {
        this.jdbc = jdbc;
        this.publisher = publisher;
        this.audit = audit;
    }

    @GetMapping
    public List<Team> list(HttpServletRequest req) {
        Caller c = Caller.from(req);
        return jdbc.sql("""
                        SELECT t.id, t.name, t.daily_budget_micros,
                               (SELECT count(*) FROM virtual_keys k WHERE k.org_id = t.org_id AND k.team_id = t.id AND k.revoked_at IS NULL) AS active_keys
                        FROM teams t WHERE t.org_id = ? ORDER BY t.name""")
                .param(c.orgId()).query(Team.class).list();
    }

    @PostMapping
    @Transactional
    public Team create(@Valid @RequestBody TeamRequest body, HttpServletRequest req) {
        Caller c = Caller.from(req).requireAdmin();
        String id = body.name().trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (id.isEmpty()) throw new IllegalArgumentException("Team name needs letters or digits");
        jdbc.sql("INSERT INTO teams (id, org_id, name, daily_budget_micros) VALUES (?, ?, ?, ?)")
                .params(id, c.orgId(), body.name().trim(), body.dailyBudgetMicros()).update();
        audit.record(c, "team.create", id, body.name());
        publisher.publish(c.orgId());
        return new Team(id, body.name().trim(), body.dailyBudgetMicros(), 0);
    }

    @PutMapping("/{id}")
    @Transactional
    public Team update(@PathVariable String id, @Valid @RequestBody TeamRequest body, HttpServletRequest req) {
        Caller c = Caller.from(req).requireAdmin();
        int n = jdbc.sql("UPDATE teams SET name = ?, daily_budget_micros = ? WHERE org_id = ? AND id = ?")
                .params(body.name().trim(), body.dailyBudgetMicros(), c.orgId(), id).update();
        if (n == 0) throw new NoSuchElementException("No team " + id);
        audit.record(c, "team.update", id, "budget " + body.dailyBudgetMicros() + " micro-USD/day");
        publisher.publish(c.orgId());
        return list(req).stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
    }

    public record Team(String id, String name, long dailyBudgetMicros, long activeKeys) {
    }

    public record TeamRequest(@NotBlank String name, @Min(0) long dailyBudgetMicros) {
    }
}
