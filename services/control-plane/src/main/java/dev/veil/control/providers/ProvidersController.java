package dev.veil.control.providers;

import dev.veil.contracts.OrgConfig;
import dev.veil.control.config.Caller;
import dev.veil.control.config.Crypto;
import dev.veil.control.config.JsonCodec;
import dev.veil.control.publish.AdminAudit;
import dev.veil.control.publish.ConfigPublisher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;

import java.net.URI;
import java.util.List;
import java.util.NoSuchElementException;

/** Upstream LLM providers and model routes. Provider API keys are write-only: stored encrypted, never returned. */
@RestController
@RequestMapping("/api")
public class ProvidersController {

    private final JdbcClient jdbc;
    private final Crypto crypto;
    private final JsonCodec json;
    private final ConfigPublisher publisher;
    private final AdminAudit audit;

    public ProvidersController(JdbcClient jdbc, Crypto crypto, JsonCodec json, ConfigPublisher publisher, AdminAudit audit) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.json = json;
        this.publisher = publisher;
        this.audit = audit;
    }

    public record ProviderView(String id, String name, String kind, String baseUrl, boolean hasApiKey, boolean local, boolean enabled) {
    }

    /** {@code apiKey}: null keeps the stored key, "" removes it. */
    public record ProviderRequest(@NotBlank String name, @NotNull OrgConfig.ProviderKind kind, String baseUrl, String apiKey,
                                  boolean local, boolean enabled) {
    }

    public record RouteRequest(@NotEmpty List<OrgConfig.RouteConfig.Target> targets, @PositiveOrZero double inputUsdPerMTok,
                               @PositiveOrZero double outputUsdPerMTok) {
    }

    @GetMapping("/providers")
    public List<ProviderView> providers(HttpServletRequest req) {
        return jdbc.sql("""
                        SELECT id, name, kind, base_url, api_key_ciphertext IS NOT NULL AS has_api_key, local, enabled
                        FROM providers WHERE org_id = ? ORDER BY name""")
                .param(Caller.from(req).orgId()).query(ProviderView.class).list();
    }

    @PutMapping("/providers/{id}")
    @Transactional
    public ProviderView upsert(@PathVariable String id, @Valid @RequestBody ProviderRequest body, HttpServletRequest req) {
        Caller c = Caller.from(req).requireAdmin();
        if (!id.matches("[a-z0-9][a-z0-9-]{1,40}")) throw new IllegalArgumentException("Provider id: lowercase letters, digits and dashes");
        if (body.kind() != OrgConfig.ProviderKind.ECHO) {
            if (body.baseUrl() == null || body.baseUrl().isBlank()) throw new IllegalArgumentException("Base URL is required");
            URI uri = URI.create(body.baseUrl().trim());
            if (uri.getScheme() == null || !uri.getScheme().startsWith("http")) throw new IllegalArgumentException("Base URL must be http(s)");
        }
        String ciphertext = body.apiKey() == null ? null : body.apiKey().isBlank() ? "" : crypto.encrypt(body.apiKey().trim());
        jdbc.sql("""
                        INSERT INTO providers (id, org_id, name, kind, base_url, api_key_ciphertext, local, enabled)
                        VALUES (:id, :org, :name, :kind, :url, nullif(:key, ''), :local, :enabled)
                        ON CONFLICT (org_id, id) DO UPDATE SET name = excluded.name, kind = excluded.kind, base_url = excluded.base_url,
                          api_key_ciphertext = CASE WHEN :keyGiven THEN nullif(:key, '') ELSE providers.api_key_ciphertext END,
                          local = excluded.local, enabled = excluded.enabled""")
                .param("id", id).param("org", c.orgId()).param("name", body.name().trim()).param("kind", body.kind().name())
                .param("url", body.baseUrl() == null ? null : body.baseUrl().trim()).param("key", ciphertext == null ? "" : ciphertext)
                .param("keyGiven", ciphertext != null).param("local", body.local()).param("enabled", body.enabled()).update();
        audit.record(c, "provider.upsert", id, body.kind() + (body.apiKey() != null ? " (key changed)" : ""));
        publisher.publish(c.orgId());
        return providers(req).stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    @GetMapping("/routes")
    public List<OrgConfig.RouteConfig> routes(HttpServletRequest req) {
        return jdbc.sql("SELECT model, targets::text, input_usd_per_mtok, output_usd_per_mtok FROM routes WHERE org_id = ? ORDER BY model")
                .param(Caller.from(req).orgId()).query((rs, n) -> new OrgConfig.RouteConfig(rs.getString(1),
                        json.read(rs.getString(2), new TypeReference<List<OrgConfig.RouteConfig.Target>>() {
                        }), rs.getDouble(3), rs.getDouble(4))).list();
    }

    @PutMapping("/routes/{model}")
    @Transactional
    public OrgConfig.RouteConfig upsertRoute(@PathVariable String model, @Valid @RequestBody RouteRequest body, HttpServletRequest req) {
        Caller c = Caller.from(req).requireAdmin();
        if (!model.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,80}")) throw new IllegalArgumentException("Invalid model name");
        for (var t : body.targets()) {
            boolean exists = jdbc.sql("SELECT count(*) FROM providers WHERE org_id = ? AND id = ?").params(c.orgId(), t.providerId())
                    .query(Integer.class).single() > 0;
            if (!exists) throw new IllegalArgumentException("Unknown provider " + t.providerId());
            if (t.upstreamModel() == null || t.upstreamModel().isBlank()) throw new IllegalArgumentException("Each target needs an upstream model");
        }
        jdbc.sql("""
                        INSERT INTO routes (org_id, model, targets, input_usd_per_mtok, output_usd_per_mtok) VALUES (?, ?, ?::jsonb, ?, ?)
                        ON CONFLICT (org_id, model) DO UPDATE SET targets = excluded.targets,
                          input_usd_per_mtok = excluded.input_usd_per_mtok, output_usd_per_mtok = excluded.output_usd_per_mtok""")
                .params(c.orgId(), model, json.write(body.targets()), body.inputUsdPerMTok(), body.outputUsdPerMTok()).update();
        audit.record(c, "route.upsert", model, body.targets().size() + " targets");
        publisher.publish(c.orgId());
        return new OrgConfig.RouteConfig(model, body.targets(), body.inputUsdPerMTok(), body.outputUsdPerMTok());
    }

    @DeleteMapping("/routes/{model}")
    @Transactional
    public void deleteRoute(@PathVariable String model, HttpServletRequest req) {
        Caller c = Caller.from(req).requireAdmin();
        if (jdbc.sql("DELETE FROM routes WHERE org_id = ? AND model = ?").params(c.orgId(), model).update() == 0) {
            throw new NoSuchElementException("No route " + model);
        }
        audit.record(c, "route.delete", model, null);
        publisher.publish(c.orgId());
    }
}
