package dev.veil.control.publish;

import dev.veil.contracts.KeyInfo;
import dev.veil.contracts.OrgConfig;
import dev.veil.control.keys.KeyService;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Service-to-service endpoints for the proxy's cold start and cache misses. Not routed by the gateway, and
 * blocked by the Kubernetes NetworkPolicy for everything except the proxy and insight-service.
 */
@RestController
@RequestMapping("/internal")
public class InternalController {

    private final KeyService keys;
    private final ConfigPublisher publisher;
    private final JdbcClient jdbc;

    public InternalController(KeyService keys, ConfigPublisher publisher, JdbcClient jdbc) {
        this.keys = keys;
        this.publisher = publisher;
        this.jdbc = jdbc;
    }

    @GetMapping("/keys/{hash}")
    public ResponseEntity<KeyInfo> key(@PathVariable String hash) {
        return keys.byHash(hash).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/orgs")
    public List<String> orgs() {
        return jdbc.sql("SELECT id FROM orgs ORDER BY id").query(String.class).list();
    }

    @GetMapping("/config/{orgId}")
    public OrgConfig config(@PathVariable String orgId) {
        return publisher.current(orgId);
    }

    /** Salt for value fingerprints (insight-service and the proxy both need it). */
    @GetMapping("/orgs/{orgId}/salt")
    public Map<String, String> salt(@PathVariable String orgId) {
        return Map.of("salt", jdbc.sql("SELECT fingerprint_salt FROM orgs WHERE id = ?").param(orgId).query(String.class).single());
    }
}
