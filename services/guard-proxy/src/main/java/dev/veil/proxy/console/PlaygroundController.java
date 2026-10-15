package dev.veil.proxy.console;

import dev.veil.contracts.KeyInfo;
import dev.veil.detect.Finding;
import dev.veil.policy.Policy;
import dev.veil.proxy.auth.ProxyException;
import dev.veil.proxy.chat.ChatPipeline;
import dev.veil.proxy.config.JsonCodec;
import dev.veil.proxy.llm.Payloads;
import dev.veil.proxy.scan.ScanService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The console's playground: runs the real pipeline (same detection, policy, redaction, routing and audit as
 * production traffic) and returns the full trace: what the user wrote, what the model saw, what it answered, and
 * what the user got back, with timings.
 */
@RestController
public class PlaygroundController {

    private final ChatPipeline pipeline;
    private final ScanService scans;
    private final JsonCodec json;

    public PlaygroundController(ChatPipeline pipeline, ScanService scans, JsonCodec json) {
        this.pipeline = pipeline;
        this.scans = scans;
        this.json = json;
    }

    public record PlaygroundScan(String text, String team, String source, Policy policy) {
    }

    public record PlaygroundChat(String prompt, String system, String team, String model) {
    }

    /** Live highlighting as you type, and the policy editor's dry run (with a draft policy). */
    @PostMapping("/api/playground/scan")
    public Mono<ScanService.Result> scan(@RequestBody PlaygroundScan body, ServerWebExchange exchange) {
        ConsoleCaller c = ConsoleCaller.from(exchange.getRequest());
        String source = "extension".equals(body.source()) ? "extension" : "api";
        return scans.scan(key(c, body.team()), body.text(), source, body.policy(), true);
    }

    @PostMapping("/api/playground/chat")
    public Mono<Map<String, Object>> chat(@RequestBody PlaygroundChat body, ServerWebExchange exchange) {
        ConsoleCaller c = ConsoleCaller.from(exchange.getRequest());
        if (body.prompt() == null || body.prompt().isBlank()) return Mono.error(ProxyException.badRequest("Write a prompt first"));
        ObjectNode request = json.object();
        request.put("model", body.model() == null || body.model().isBlank() ? "gpt-4o-mini" : body.model());
        ArrayNode messages = request.putArray("messages");
        if (body.system() != null && !body.system().isBlank()) messages.addObject().put("role", "system").put("content", body.system());
        messages.addObject().put("role", "user").put("content", body.prompt());
        long t0 = System.nanoTime();
        return pipeline.prepare(key(c, body.team()), c.name() == null ? c.userId() : c.name(), request)
                .flatMap(ctx -> pipeline.complete(ctx).map(out -> trace(ctx, out, t0, null)))
                .onErrorResume(ProxyException.class, e -> Mono.just(Map.of(
                        "status", e.status().value(), "error", Map.of("message", e.getMessage(), "code", e.code(), "details", e.details()))));
    }

    private Map<String, Object> trace(ChatPipeline.Ctx ctx, ObjectNode out, long t0, String error) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("status", 200);
        t.put("requestId", ctx.requestId());
        t.put("action", ctx.decision().action().name());
        t.put("reasons", ctx.decision().reasons());
        List<Map<String, Object>> findings = new ArrayList<>();
        for (var fd : ctx.decision().findings()) {
            Finding f = fd.finding();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", f.type().name());
            m.put("label", f.type().label());
            m.put("severity", f.type().severity().name());
            m.put("detail", f.detail());
            m.put("confidence", Math.round(f.confidence() * 100) / 100.0);
            m.put("action", fd.action().name());
            m.put("rule", fd.ruleId());
            m.put("start", f.start());
            m.put("end", f.end());
            findings.add(m);
        }
        t.put("findings", findings);
        JsonNode sent = ctx.redactedBody().path("messages");
        t.put("sentToModel", Payloads.contentText(sent.get(sent.size() - 1)));
        t.put("placeholders", ctx.vault().size());
        t.put("modelAnswer", ctx.upstreamAnswer());
        t.put("finalAnswer", ctx.finalAnswer());
        t.put("servedBy", ctx.servedBy());
        t.put("routedLocally", ctx.localOnly());
        t.put("cacheHit", ctx.cacheHit());
        t.put("attempts", ctx.attempts());
        t.put("outputFindings", ctx.outputFindings().stream().map(f -> f.type().name()).toList());
        t.put("tokens", Map.of("in", ctx.tokensIn(), "out", ctx.tokensOut()));
        t.put("costMicros", ctx.costMicros());
        t.put("timings", Map.of("detectMicros", ctx.scan().micros(), "detectors", ctx.scan().detectorMicros(),
                "upstreamMs", ctx.upstreamMs(), "totalMs", (System.nanoTime() - t0) / 1_000_000));
        t.put("response", out);
        return t;
    }

    private static KeyInfo key(ConsoleCaller c, String team) {
        String t = team == null || team.isBlank() ? "support" : team;
        return new KeyInfo("playground", c.orgId(), t, t, "Console playground", "API", 0, 0, false);
    }
}
