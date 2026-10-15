package dev.veil.control.publish;

import dev.veil.control.config.Caller;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/** Records who changed what in the console. Auditors ask for this as much as for the request log. */
@Component
public class AdminAudit {

    private final JdbcClient jdbc;

    public AdminAudit(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(Caller caller, String action, String target, String detail) {
        jdbc.sql("INSERT INTO admin_audit (org_id, actor, action, target, detail) VALUES (?, ?, ?, ?, ?)")
                .params(caller.orgId(), caller.name() == null ? caller.userId() : caller.name(), action, target, detail).update();
    }

    public List<Entry> recent(String orgId, int limit) {
        return jdbc.sql("SELECT id, actor, action, target, detail, at FROM admin_audit WHERE org_id = ? ORDER BY id DESC LIMIT ?")
                .params(orgId, limit).query(Entry.class).list();
    }

    public record Entry(long id, String actor, String action, String target, String detail, Instant at) {
    }
}
