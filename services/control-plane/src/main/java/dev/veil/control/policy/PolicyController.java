package dev.veil.control.policy;

import dev.veil.control.config.Caller;
import dev.veil.control.publish.AdminAudit;
import dev.veil.detect.EntityType;
import dev.veil.policy.Policy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/policy")
public class PolicyController {

    private final PolicyService policies;
    private final PolicyCopilot copilot;
    private final JdbcClient jdbc;
    private final AdminAudit audit;

    public PolicyController(PolicyService policies, PolicyCopilot copilot, JdbcClient jdbc, AdminAudit audit) {
        this.policies = policies;
        this.copilot = copilot;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @GetMapping
    public Policy active(HttpServletRequest req) {
        return policies.active(Caller.from(req).orgId());
    }

    @GetMapping("/versions")
    public List<PolicyService.Version> versions(HttpServletRequest req) {
        return policies.versions(Caller.from(req).orgId());
    }

    @GetMapping("/versions/{version}")
    public Policy version(@PathVariable int version, HttpServletRequest req) {
        return policies.version(Caller.from(req).orgId(), version);
    }

    @PutMapping
    public Policy publish(@RequestBody Policy draft, @RequestParam(required = false) String note, HttpServletRequest req) {
        return policies.publish(Caller.from(req).requireAdmin(), draft, note);
    }

    @PostMapping("/versions/{version}/rollback")
    public Policy rollback(@PathVariable int version, HttpServletRequest req) {
        return policies.rollback(Caller.from(req).requireAdmin(), version);
    }

    /** Drafts rules from plain English. Viewers may try it; only admins can publish the result. */
    @PostMapping("/copilot")
    public PolicyCopilot.Draft copilot(@RequestBody Map<String, String> body, HttpServletRequest req) {
        Caller c = Caller.from(req);
        Map<String, String> teams = new LinkedHashMap<>();
        jdbc.sql("SELECT name, id FROM teams WHERE org_id = ?").param(c.orgId())
                .query((rs, n) -> teams.put(rs.getString(1), rs.getString(2))).list();
        PolicyCopilot.Draft d = copilot.draft(body.get("instruction"), teams);
        audit.record(c, "policy.copilot", d.mode(), d.rules().size() + " rules drafted");
        return d;
    }

    /** The detection catalogue, so the console can show types, categories, severities and regulations. */
    @GetMapping("/catalogue")
    public List<Map<String, Object>> catalogue() {
        return Arrays.stream(EntityType.values()).map(t -> Map.<String, Object>of(
                "type", t.name(), "label", t.label(), "category", t.category().name(), "severity", t.severity().name(),
                "spanBased", t.spanBased(), "regulations", t.regulations())).toList();
    }

    @GetMapping("/changes")
    public List<AdminAudit.Entry> changes(HttpServletRequest req) {
        return audit.recent(Caller.from(req).orgId(), 100);
    }
}
