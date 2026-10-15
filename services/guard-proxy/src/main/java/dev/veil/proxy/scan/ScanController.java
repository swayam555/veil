package dev.veil.proxy.scan;

import dev.veil.proxy.auth.KeyResolver;
import dev.veil.proxy.auth.ProxyException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Scan API (any system can check text before storing or sending it) and the browser extension's endpoints.
 * Both return offsets and types, never the values: the caller already has the text.
 */
@RestController
public class ScanController {

    /** AI chat sites the extension watches. Sent to the extension so the list can change without a new release. */
    public static final List<String> AI_SITES = List.of("chatgpt.com", "chat.openai.com", "claude.ai", "gemini.google.com",
            "copilot.microsoft.com", "perplexity.ai", "chat.deepseek.com", "chat.mistral.ai", "poe.com", "meta.ai");
    private static final Set<String> KINDS = Set.of("PASTE", "SUBMIT");

    private final KeyResolver keys;
    private final ScanService scans;

    public ScanController(KeyResolver keys, ScanService scans) {
        this.keys = keys;
        this.scans = scans;
    }

    public record ScanRequest(String text) {
    }

    public record ExtensionScan(String text, String site, String kind, String user) {
    }

    public record ExtensionEvent(String kind, String site, String user) {
    }

    @PostMapping("/v1/scan")
    public Mono<ScanService.Result> scan(@RequestBody ScanRequest body, ServerWebExchange exchange) {
        long t0 = System.currentTimeMillis();
        return keys.resolve(KeyResolver.extract(exchange.getRequest()), "API")
                .flatMap(key -> scans.scan(key, body.text(), "api", null, false)
                        .doOnNext(r -> scans.audit(key, r, "scan", "SCAN", null,
                                exchange.getRequest().getHeaders().getFirst("x-veil-user"), body.text(), System.currentTimeMillis() - t0)));
    }

    @PostMapping("/v1/extension/scan")
    public Mono<ScanService.Result> extensionScan(@RequestBody ExtensionScan body, ServerWebExchange exchange) {
        long t0 = System.currentTimeMillis();
        String kind = body.kind() == null ? "PASTE" : body.kind().toUpperCase();
        if (!KINDS.contains(kind)) return Mono.error(ProxyException.badRequest("kind must be PASTE or SUBMIT"));
        return keys.resolve(KeyResolver.extract(exchange.getRequest()), "EXTENSION")
                .flatMap(key -> scans.scan(key, body.text(), "extension", null, false)
                        .doOnNext(r -> scans.audit(key, r, "extension", kind, site(body.site()), user(body.user()), body.text(),
                                System.currentTimeMillis() - t0)));
    }

    @PostMapping("/v1/extension/events")
    public Mono<Map<String, Object>> extensionEvent(@RequestBody ExtensionEvent body, ServerWebExchange exchange) {
        if (!"VISIT".equalsIgnoreCase(body.kind())) return Mono.error(ProxyException.badRequest("kind must be VISIT"));
        return keys.resolve(KeyResolver.extract(exchange.getRequest()), "EXTENSION").map(key -> {
            scans.auditVisit(key, site(body.site()), user(body.user()));
            return Map.of("ok", true);
        });
    }

    @GetMapping("/v1/extension/config")
    public Mono<Map<String, Object>> extensionConfig(ServerWebExchange exchange) {
        return keys.resolve(KeyResolver.extract(exchange.getRequest()), "EXTENSION").map(key -> {
            var org = scans.org(key.orgId()).config();
            return Map.of("org", org.orgName(), "team", key.teamName(), "policyVersion", org.policy().version(), "sites", AI_SITES);
        });
    }

    private static String site(String s) {
        if (s == null) return null;
        String host = s.replaceFirst("^https?://", "").replaceFirst("/.*$", "").toLowerCase();
        return host.length() > 100 ? host.substring(0, 100) : host;
    }

    private static String user(String u) {
        return u == null ? null : u.length() > 120 ? u.substring(0, 120) : u;
    }
}
