package dev.veil.proxy.chat;

import dev.veil.contracts.OrgConfig;
import dev.veil.proxy.auth.KeyResolver;
import dev.veil.proxy.auth.ProxyException;
import dev.veil.proxy.state.ConfigStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** OpenAI-compatible endpoints. Point any OpenAI SDK's base URL at {@code https://<veil>/v1} and use a Veil key. */
@RestController
public class ChatController {

    private final KeyResolver keys;
    private final ChatPipeline pipeline;
    private final ConfigStore configs;

    public ChatController(KeyResolver keys, ChatPipeline pipeline, ConfigStore configs) {
        this.keys = keys;
        this.pipeline = pipeline;
        this.configs = configs;
    }

    @PostMapping(value = "/v1/chat/completions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<?>> chat(@RequestBody JsonNode body, ServerWebExchange exchange) {
        if (!body.isObject()) return Mono.error(ProxyException.badRequest("Request body must be a JSON object"));
        String actor = exchange.getRequest().getHeaders().getFirst("x-veil-user");
        return keys.resolve(KeyResolver.extract(exchange.getRequest()), "API")
                .flatMap(key -> pipeline.prepare(key, actor, (ObjectNode) body, noCache(exchange)))
                .flatMap(ctx -> {
                    var headers = ResponseEntity.ok()
                            .header("x-veil-request-id", ctx.requestId())
                            .header("x-veil-action", ctx.decision.action().name())
                            .header("x-veil-redactions", String.valueOf(ctx.decision.toRedact().size()));
                    if (ctx.stream) {
                        return pipeline.stream(ctx).map(flux -> headers.header("x-veil-cache", ctx.cacheHit ? "hit" : "miss")
                                .contentType(MediaType.TEXT_EVENT_STREAM).body(flux));
                    }
                    return pipeline.complete(ctx).map(out -> headers.header("x-veil-cache", ctx.cacheHit ? "hit" : "miss")
                            .contentType(MediaType.APPLICATION_JSON).body(out));
                });
    }

    private static boolean noCache(ServerWebExchange exchange) {
        String cc = exchange.getRequest().getHeaders().getFirst("Cache-Control");
        return cc != null && (cc.contains("no-cache") || cc.contains("no-store"));
    }

    /** The models this key can use, in OpenAI's list format (SDKs and tools call this to populate pickers). */
    @GetMapping("/v1/models")
    public Mono<Map<String, Object>> models(ServerWebExchange exchange) {
        return keys.resolve(KeyResolver.extract(exchange.getRequest()), null).map(key -> {
            var org = configs.get(key.orgId()).orElseThrow(() -> new ProxyException(HttpStatus.SERVICE_UNAVAILABLE,
                    "server_error", "config_not_loaded", "Configuration not loaded yet"));
            List<Map<String, Object>> data = org.config().routes().stream().map(OrgConfig.RouteConfig::model)
                    .filter(m -> org.config().policy().allowedModels().isEmpty() || org.config().policy().allowedModels().contains(m))
                    .map(m -> Map.<String, Object>of("id", m, "object", "model", "created", Instant.now().getEpochSecond(), "owned_by", "veil"))
                    .toList();
            return Map.of("object", "list", "data", data);
        });
    }
}
