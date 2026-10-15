package dev.veil.proxy.chat;

import dev.veil.contracts.AuditEvent;
import dev.veil.contracts.KeyInfo;
import dev.veil.contracts.OrgConfig;
import dev.veil.detect.EntityType;
import dev.veil.detect.Finding;
import dev.veil.detect.RedactionVault;
import dev.veil.detect.StreamingRehydrator;
import dev.veil.policy.Action;
import dev.veil.policy.Decision;
import dev.veil.policy.Policy;
import dev.veil.policy.PolicyEngine;
import dev.veil.proxy.audit.AuditPublisher;
import dev.veil.proxy.audit.Audits;
import dev.veil.proxy.auth.ProxyException;
import dev.veil.proxy.cache.SemanticCache;
import dev.veil.proxy.config.Crypto;
import dev.veil.proxy.config.JsonCodec;
import dev.veil.proxy.config.ProxyProperties;
import dev.veil.proxy.detect.DetectionService;
import dev.veil.proxy.limits.Limits;
import dev.veil.proxy.llm.Completion;
import dev.veil.proxy.llm.Payloads;
import dev.veil.proxy.llm.ProviderRouter;
import dev.veil.proxy.state.ConfigStore;
import dev.veil.proxy.state.OrgState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The life of one chat request: authenticate → admit (rate, budget) → detect → decide → redact → (semantic cache)
 * → route with failover → scan the output → rehydrate (streamed or not) → account for cost → audit.
 */
@Service
public class ChatPipeline {

    private final ConfigStore configs;
    private final Limits limits;
    private final DetectionService detection;
    private final ProviderRouter router;
    private final SemanticCache cache;
    private final AuditPublisher audit;
    private final JsonCodec json;
    private final ProxyProperties props;
    private final MeterRegistry meters;

    public ChatPipeline(ConfigStore configs, Limits limits, DetectionService detection, ProviderRouter router,
                        SemanticCache cache, AuditPublisher audit, JsonCodec json, ProxyProperties props, MeterRegistry meters) {
        this.configs = configs;
        this.limits = limits;
        this.detection = detection;
        this.router = router;
        this.cache = cache;
        this.audit = audit;
        this.json = json;
        this.props = props;
        this.meters = meters;
    }

    /** Where a piece of text lives in the request: messages[message].content or messages[message].content[part].text. */
    record TextRef(int message, int part, String role, String text) {
    }

    /** Everything known about a request once it has been checked and redacted. */
    public static final class Ctx {
        final String requestId = "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        final long startNanos = System.nanoTime();
        final Instant at = Instant.now();
        KeyInfo key;
        String actor;
        String kind = "CHAT";
        OrgState org;
        ObjectNode original;
        String model;
        boolean stream;
        boolean includeUsage;
        List<TextRef> texts;
        DetectionService.Scan scan;
        Decision decision;
        final RedactionVault vault = new RedactionVault();
        ObjectNode redacted;
        List<ProviderRouter.Target> targets;
        boolean localOnly;
        boolean cacheEligible;
        boolean bypassCache;
        String cacheContext;
        String cachePrompt;
        boolean cacheHit;
        double cacheDistance = -1;
        long upstreamMs;
        List<ProviderRouter.Attempt> attempts = List.of();
        String servedBy;
        String upstreamAnswer = "";
        String finalAnswer = "";
        List<Finding> outputFindings = List.of();
        int tokensIn;
        int tokensOut;
        long costMicros;

        public String requestId() {
            return requestId;
        }

        public Decision decision() {
            return decision;
        }

        public ObjectNode redactedBody() {
            return redacted;
        }

        public RedactionVault vault() {
            return vault;
        }

        public String upstreamAnswer() {
            return upstreamAnswer;
        }

        public String finalAnswer() {
            return finalAnswer;
        }

        public String servedBy() {
            return servedBy;
        }

        public boolean localOnly() {
            return localOnly;
        }

        public boolean cacheHit() {
            return cacheHit;
        }

        public List<ProviderRouter.Attempt> attempts() {
            return attempts;
        }

        public List<Finding> outputFindings() {
            return outputFindings;
        }

        public int tokensIn() {
            return tokensIn;
        }

        public int tokensOut() {
            return tokensOut;
        }

        public long costMicros() {
            return costMicros;
        }

        public DetectionService.Scan scan() {
            return scan;
        }

        public long upstreamMs() {
            return upstreamMs;
        }

        long elapsedMs() {
            return (System.nanoTime() - startNanos) / 1_000_000;
        }
    }

    // ---------------------------------------------------------------- preparation

    public Mono<Ctx> prepare(KeyInfo key, String actor, ObjectNode body) {
        return prepare(key, actor, body, false);
    }

    /** @param bypassCache the client sent {@code Cache-Control: no-cache}: always ask the provider (the answer is still cached) */
    public Mono<Ctx> prepare(KeyInfo key, String actor, ObjectNode body, boolean bypassCache) {
        Ctx ctx = new Ctx();
        ctx.bypassCache = bypassCache;
        ctx.key = key;
        ctx.actor = actor;
        ctx.original = body;
        ctx.model = body.path("model").asString("");
        ctx.stream = body.path("stream").asBoolean(false);
        ctx.includeUsage = body.path("stream_options").path("include_usage").asBoolean(false);
        if (ctx.model.isBlank()) return Mono.error(ProxyException.badRequest("'model' is required"));
        if (!body.path("messages").isArray() || body.path("messages").isEmpty()) {
            return Mono.error(ProxyException.badRequest("'messages' must be a non-empty array"));
        }
        ctx.texts = texts(body);
        int chars = ctx.texts.stream().mapToInt(t -> t.text().length()).sum();
        if (chars > props.maxBodyChars()) {
            return Mono.error(new ProxyException(HttpStatus.PAYLOAD_TOO_LARGE, "invalid_request_error", "request_too_large",
                    "Request has " + chars + " characters of text; the limit is " + props.maxBodyChars()));
        }
        OrgState org = configs.get(key.orgId()).orElse(null);
        if (org == null) {
            return Mono.error(new ProxyException(HttpStatus.SERVICE_UNAVAILABLE, "server_error", "config_not_loaded",
                    "Veil is still loading your organisation's policy; retry in a few seconds"));
        }
        ctx.org = org;
        return limits.checkRate(key)
                .onErrorResume(ProxyException.class, e -> {
                    auditRejected(ctx, "RATE_LIMITED", e.getMessage());
                    return Mono.error(e);
                })
                .then(limits.checkBudget(key, org.config()))
                .onErrorResume(ProxyException.class, e -> {
                    if ("budget_exceeded".equals(e.code())) auditRejected(ctx, "BUDGET_EXCEEDED", e.getMessage());
                    return Mono.error(e);
                })
                .then(detection.scan(org, ctx.texts.stream().map(TextRef::text).toList()))
                .map(scan -> decide(ctx, scan));
    }

    private Ctx decide(Ctx ctx, DetectionService.Scan scan) {
        ctx.scan = scan;
        Policy policy = ctx.org.config().policy();
        ctx.decision = PolicyEngine.decide(policy, scan.all(), new PolicyEngine.Context(ctx.key.teamId(), "api", ctx.model));
        if (ctx.decision.blocked()) {
            meters.counter("veil.requests", "action", "BLOCK").increment();
            publishAudit(ctx, "BLOCKED", null);
            List<String> types = ctx.decision.findings().stream().filter(f -> f.action() == Action.BLOCK)
                    .map(f -> f.finding().type().name()).distinct().toList();
            throw ProxyException.blocked("Veil blocked this request: " + String.join("; ", ctx.decision.reasons()),
                    ctx.decision.reasons(), types, ctx.requestId);
        }
        ctx.localOnly = ctx.decision.action() == Action.ROUTE_LOCAL;
        ctx.redacted = redactBody(ctx);
        if (policy.maxOutputTokens() > 0) clampMaxTokens(ctx.redacted, policy.maxOutputTokens());
        ctx.targets = router.targets(ctx.org, ctx.model, ctx.localOnly);
        prepareCache(ctx, policy);
        return ctx;
    }

    static List<TextRef> texts(JsonNode body) {
        List<TextRef> out = new ArrayList<>();
        int i = 0;
        for (JsonNode m : body.path("messages").values()) {
            String role = m.path("role").asString("user");
            JsonNode c = m.path("content");
            if (c.isString()) {
                out.add(new TextRef(i, -1, role, c.asString()));
            } else if (c.isArray()) {
                int j = 0;
                for (JsonNode part : c.values()) {
                    if ("text".equals(part.path("type").asString("")) && part.path("text").isString()) {
                        out.add(new TextRef(i, j, role, part.path("text").asString()));
                    }
                    j++;
                }
            }
            i++;
        }
        return out;
    }

    /** A copy of the request in which every finding marked REDACT is replaced by its placeholder. */
    private ObjectNode redactBody(Ctx ctx) {
        ObjectNode copy = ctx.original.deepCopy();
        Set<Finding> toRedact = Collections.newSetFromMap(new IdentityHashMap<>());
        toRedact.addAll(ctx.decision.toRedact());
        ArrayNode messages = (ArrayNode) copy.path("messages");
        for (int k = 0; k < ctx.texts.size(); k++) {
            TextRef ref = ctx.texts.get(k);
            List<Finding> mine = ctx.scan.perText().get(k).stream().filter(toRedact::contains).toList();
            if (mine.isEmpty()) continue;
            String redacted = ctx.vault.redact(ref.text(), mine);
            ObjectNode message = (ObjectNode) messages.get(ref.message());
            if (ref.part() < 0) message.put("content", redacted);
            else ((ObjectNode) message.path("content").get(ref.part())).put("text", redacted);
        }
        return copy;
    }

    private static void clampMaxTokens(ObjectNode body, int cap) {
        for (String field : List.of("max_tokens", "max_completion_tokens")) {
            if (body.has(field) && body.path(field).asInt(0) > cap) body.put(field, cap);
        }
        if (!body.has("max_tokens") && !body.has("max_completion_tokens")) body.put("max_tokens", cap);
    }

    private void prepareCache(Ctx ctx, Policy policy) {
        JsonNode messages = ctx.redacted.path("messages");
        JsonNode last = messages.get(messages.size() - 1);
        ctx.cacheEligible = policy.semanticCache() && cache.available() && !ctx.localOnly
                && !ctx.redacted.has("tools") && !ctx.redacted.has("functions") && ctx.redacted.path("n").asInt(1) == 1
                && "user".equals(last.path("role").asString(""));
        if (!ctx.cacheEligible) return;
        StringBuilder context = new StringBuilder(ctx.model).append('|').append(ctx.redacted.path("temperature").asString(""));
        for (int i = 0; i < messages.size() - 1; i++) {
            context.append('|').append(messages.get(i).path("role").asString("")).append(':').append(Payloads.contentText(messages.get(i)));
        }
        ctx.cacheContext = Crypto.sha256(context.toString());
        ctx.cachePrompt = Payloads.contentText(last);
    }

    // ---------------------------------------------------------------- non-streaming

    public Mono<ObjectNode> complete(Ctx ctx) {
        long t0 = System.nanoTime();
        Mono<Completion> answer = (ctx.cacheEligible && !ctx.bypassCache
                ? cache.lookup(ctx.key.orgId(), ctx.model, ctx.cacheContext, ctx.cachePrompt)
                : Mono.just(java.util.Optional.<SemanticCache.Hit>empty()))
                .flatMap(hit -> {
                    if (hit.isPresent()) {
                        ctx.cacheHit = true;
                        ctx.cacheDistance = hit.get().distance();
                        ctx.servedBy = "semantic-cache";
                        ObjectNode cached = (ObjectNode) json.tree(hit.get().answerJson());
                        return Mono.just(new Completion(cached, Payloads.promptTokens(ctx.redacted), 0));
                    }
                    return router.complete(ctx.org, ctx.targets, ctx.redacted).map(r -> {
                        ctx.attempts = r.attempts();
                        ctx.servedBy = r.servedBy().provider().id();
                        return r.result();
                    });
                });
        return answer
                .onErrorResume(ProxyException.class, e -> {
                    ctx.upstreamMs = (System.nanoTime() - t0) / 1_000_000;
                    publishAudit(ctx, "UPSTREAM_ERROR", null);
                    return Mono.error(e);
                })
                .flatMap(c -> {
                    ctx.upstreamMs = (System.nanoTime() - t0) / 1_000_000;
                    ObjectNode response = c.response();
                    ctx.tokensIn = c.tokensIn();
                    ctx.tokensOut = c.tokensOut();
                    List<String> outputs = new ArrayList<>();
                    for (JsonNode choice : response.path("choices").values()) {
                        outputs.add(choice.path("message").path("content").asString(""));
                    }
                    return detection.scanPatternsOnly(ctx.org, outputs).map(scan -> {
                        maskOutput(ctx, response, scan);
                        ctx.upstreamAnswer = String.join("\n", choiceTexts(response.path("choices")));
                        if (ctx.cacheEligible && !ctx.cacheHit) {
                            cache.store(ctx.key.orgId(), ctx.model, ctx.cacheContext, ctx.cachePrompt, json.write(response)).subscribe();
                        }
                        ObjectNode out = response.deepCopy();
                        for (JsonNode choice : out.path("choices").values()) {
                            ObjectNode message = (ObjectNode) choice.path("message");
                            if (message.path("content").isString()) {
                                message.put("content", ctx.vault.rehydrate(message.path("content").asString()));
                            }
                        }
                        out.put("model", ctx.model);
                        Payloads.usage(out, ctx.tokensIn, ctx.tokensOut);
                        ctx.finalAnswer = String.join("\n", choiceTexts(out.path("choices")));
                        finish(ctx);
                        return out;
                    });
                });
    }

    private static List<String> choiceTexts(JsonNode choices) {
        List<String> t = new ArrayList<>();
        for (JsonNode c : choices.values()) t.add(c.path("message").path("content").asString(""));
        return t;
    }

    /**
     * Output DLP: secrets or personal data that the MODEL produced (not ones the user sent; those are placeholders)
     * are masked when the policy would block or redact them on the way in.
     */
    private void maskOutput(Ctx ctx, ObjectNode response, DetectionService.Scan scan) {
        if (!ctx.org.config().policy().outputScanning()) return;
        List<Finding> found = new ArrayList<>();
        int i = 0;
        for (JsonNode choice : response.path("choices").values()) {
            List<Finding> fs = scan.perText().get(i++);
            if (fs.isEmpty()) continue;
            Decision d = PolicyEngine.decide(ctx.org.config().policy(), fs, new PolicyEngine.Context(ctx.key.teamId(), "api", null));
            List<Finding> mask = d.findings().stream()
                    .filter(fd -> fd.finding().type().spanBased() && (fd.action() == Action.BLOCK || fd.action() == Action.REDACT))
                    .map(Decision.FindingDecision::finding).sorted((a, b) -> Integer.compare(b.start(), a.start())).toList();
            if (mask.isEmpty()) continue;
            found.addAll(mask);
            ObjectNode message = (ObjectNode) choice.path("message");
            StringBuilder text = new StringBuilder(message.path("content").asString(""));
            for (Finding f : mask) text.replace(f.start(), f.end(), "[REDACTED:" + f.type().name() + "]");
            message.put("content", text.toString());
        }
        ctx.outputFindings = found;
    }

    // ---------------------------------------------------------------- streaming

    /**
     * Returns the SSE stream once the first chunk has arrived, so that a failure of every provider is still a proper
     * HTTP error (502) rather than a 200 with a broken stream.
     */
    public Mono<Flux<ServerSentEvent<String>>> stream(Ctx ctx) {
        long t0 = System.nanoTime();
        Mono<Flux<ObjectNode>> source = (ctx.cacheEligible && !ctx.bypassCache
                ? cache.lookup(ctx.key.orgId(), ctx.model, ctx.cacheContext, ctx.cachePrompt)
                : Mono.just(java.util.Optional.<SemanticCache.Hit>empty()))
                .map(hit -> {
                    if (hit.isPresent()) {
                        ctx.cacheHit = true;
                        ctx.cacheDistance = hit.get().distance();
                        ctx.servedBy = "semantic-cache";
                        return cachedAsChunks(ctx, (ObjectNode) json.tree(hit.get().answerJson()));
                    }
                    ProviderRouter.RoutedStream routed = router.stream(ctx.org, ctx.targets, ctx.redacted);
                    ctx.attempts = routed.attempts();
                    return routed.flux().doOnNext(c -> {
                        if (ctx.servedBy == null && routed.servedBy().get() != null) ctx.servedBy = routed.servedBy().get().provider().id();
                    });
                });
        return source.flatMap(upstream -> {
            Flux<ObjectNode> shared = upstream.cache();
            return shared.next()
                    .onErrorResume(ProxyException.class, e -> {
                        ctx.upstreamMs = (System.nanoTime() - t0) / 1_000_000;
                        publishAudit(ctx, "UPSTREAM_ERROR", null);
                        return Mono.error(e);
                    })
                    .map(first -> sse(ctx, shared, t0));
        });
    }

    private Flux<ObjectNode> cachedAsChunks(Ctx ctx, ObjectNode cached) {
        String id = Payloads.newId();
        String text = cached.path("choices").path(0).path("message").path("content").asString("");
        List<ObjectNode> chunks = new ArrayList<>();
        boolean first = true;
        for (int i = 0; i < text.length(); i += 24) {
            chunks.add(Payloads.chunk(id, ctx.model, text.substring(i, Math.min(text.length(), i + 24)), null, first));
            first = false;
        }
        chunks.add(Payloads.chunk(id, ctx.model, null, "stop", false));
        chunks.add(Payloads.usageChunk(id, ctx.model, Payloads.promptTokens(ctx.redacted), 0));
        return Flux.fromIterable(chunks);
    }

    private Flux<ServerSentEvent<String>> sse(Ctx ctx, Flux<ObjectNode> upstream, long t0) {
        Map<Integer, StreamingRehydrator> rehydrators = new HashMap<>();
        Map<Integer, StringBuilder> raw = new LinkedHashMap<>();
        Map<Integer, StringBuilder> rehydrated = new LinkedHashMap<>();
        AtomicInteger outTokens = new AtomicInteger(-1);
        AtomicInteger inTokens = new AtomicInteger(-1);
        AtomicReference<String> status = new AtomicReference<>("OK");

        Flux<ServerSentEvent<String>> events = upstream.concatMap(chunk -> {
            JsonNode usage = chunk.path("usage");
            if (usage.isObject()) {
                inTokens.set(usage.path("prompt_tokens").asInt(-1));
                outTokens.set(usage.path("completion_tokens").asInt(-1));
            }
            boolean usageOnly = chunk.path("choices").isEmpty();
            if (usageOnly && !ctx.includeUsage) return Flux.empty();
            ObjectNode out = chunk.deepCopy();
            out.put("model", ctx.model);
            if (!ctx.includeUsage) out.remove("usage");
            for (JsonNode choice : out.path("choices").values()) {
                int index = choice.path("index").asInt(0);
                StreamingRehydrator r = rehydrators.computeIfAbsent(index, k -> ctx.vault.streaming());
                ObjectNode delta = choice.path("delta").isObject() ? (ObjectNode) choice.path("delta") : ((ObjectNode) choice).putObject("delta");
                String piece = delta.path("content").isString() ? delta.path("content").asString() : null;
                String emitted = "";
                if (piece != null) {
                    raw.computeIfAbsent(index, k -> new StringBuilder()).append(piece);
                    emitted = r.feed(piece);
                }
                boolean finishing = choice.has("finish_reason") && !choice.path("finish_reason").isNull();
                if (finishing) emitted += r.flush();
                if (piece != null || (finishing && !emitted.isEmpty())) delta.put("content", emitted);
                rehydrated.computeIfAbsent(index, k -> new StringBuilder()).append(emitted);
            }
            return Flux.just(ServerSentEvent.builder(json.write(out)).build());
        }).onErrorResume(e -> {
            status.set("UPSTREAM_ERROR");
            ObjectNode err = json.object();
            ObjectNode inner = err.putObject("error");
            inner.put("message", "The provider failed mid-stream: " + e.getMessage());
            inner.put("type", "server_error");
            inner.put("code", "stream_interrupted");
            return Flux.just(ServerSentEvent.builder(json.write(err)).build());
        });

        return events.concatWith(Flux.just(ServerSentEvent.builder("[DONE]").build()))
                .doFinally(signal -> {
                    ctx.upstreamMs = (System.nanoTime() - t0) / 1_000_000;
                    ctx.upstreamAnswer = String.join("\n", raw.values().stream().map(StringBuilder::toString).toList());
                    ctx.finalAnswer = String.join("\n", rehydrated.values().stream().map(StringBuilder::toString).toList());
                    ctx.tokensIn = inTokens.get() >= 0 ? inTokens.get() : Payloads.promptTokens(ctx.redacted);
                    ctx.tokensOut = ctx.cacheHit ? 0 : outTokens.get() >= 0 ? outTokens.get() : Payloads.estimateTokens(ctx.upstreamAnswer);
                    if (ctx.cacheEligible && !ctx.cacheHit && "OK".equals(status.get()) && !ctx.upstreamAnswer.isEmpty()) {
                        ObjectNode asCompletion = Payloads.completion(Payloads.newId(), ctx.model, ctx.upstreamAnswer, "stop", ctx.tokensIn, ctx.tokensOut);
                        cache.store(ctx.key.orgId(), ctx.model, ctx.cacheContext, ctx.cachePrompt, json.write(asCompletion)).subscribe();
                    }
                    // Output findings in streams are recorded (the text has already been sent).
                    ctx.outputFindings = ctx.org.engineWithoutSemantic().scan(ctx.upstreamAnswer).findings().stream()
                            .filter(f -> f.type().category() == EntityType.Category.SECRET).toList();
                    finish(ctx, status.get());
                });
    }

    // ---------------------------------------------------------------- accounting and audit

    private void finish(Ctx ctx) {
        finish(ctx, "OK");
    }

    private void finish(Ctx ctx, String status) {
        ctx.costMicros = cost(ctx);
        limits.recordSpend(ctx.key, ctx.costMicros).subscribe();
        meters.counter("veil.requests", "action", ctx.decision.action().name()).increment();
        if (ctx.cacheHit) meters.counter("veil.requests.cached").increment();
        publishAudit(ctx, status, null);
    }

    private long cost(Ctx ctx) {
        if (ctx.cacheHit || ctx.servedBy == null) return 0;
        OrgConfig.ProviderConfig p = ctx.org.config().provider(ctx.servedBy);
        if (p != null && p.local()) return 0;
        OrgConfig.RouteConfig r = ctx.org.config().route(ctx.model);
        if (r == null) return 0;
        // USD per million tokens == micro-dollars per token.
        return Math.round(ctx.tokensIn * r.inputUsdPerMTok() + ctx.tokensOut * r.outputUsdPerMTok());
    }

    private void auditRejected(Ctx ctx, String status, String reason) {
        ctx.decision = new Decision(Action.ALLOW, List.of(), List.of(reason));
        publishAudit(ctx, status, reason);
    }

    void publishAudit(Ctx ctx, String status, String extraReason) {
        OrgConfig config = ctx.org == null ? null : ctx.org.config();
        String salt = config == null ? null : config.fingerprintSalt();
        List<AuditEvent.FindingSummary> findings = new ArrayList<>(Audits.summaries(ctx.decision, salt, "input"));
        for (Finding f : ctx.outputFindings) findings.add(Audits.summary(f, "MASKED", salt, "output"));
        List<String> reasons = new ArrayList<>(ctx.decision.reasons());
        if (extraReason != null && !reasons.contains(extraReason)) reasons.add(extraReason);
        if (!ctx.attempts.isEmpty() && ctx.attempts.stream().anyMatch(a -> !a.outcome().equals("ok"))) {
            for (var a : ctx.attempts) if (!a.outcome().equals("ok")) reasons.add("Failover: " + a.providerId() + " " + a.outcome());
        }
        Policy policy = config == null ? null : config.policy();
        String redactedPrompt = null;
        if (policy != null && policy.storeRedactedPrompts() && ctx.redacted != null) {
            JsonNode messages = ctx.redacted.path("messages");
            redactedPrompt = Payloads.contentText(messages.get(messages.size() - 1));
            if (redactedPrompt.length() > 4000) redactedPrompt = redactedPrompt.substring(0, 4000) + "…";
        } else if (policy != null && policy.storeRedactedPrompts() && "BLOCKED".equals(status) && ctx.texts != null && !ctx.texts.isEmpty()) {
            // Blocked prompts are stored with every finding masked, so reviewers see context but never the values.
            int last = ctx.texts.size() - 1;
            List<Finding> all = ctx.scan.perText().get(last).stream().filter(f -> f.type().spanBased()).toList();
            redactedPrompt = new RedactionVault().redact(ctx.texts.get(last).text(), all);
            if (redactedPrompt.length() > 4000) redactedPrompt = redactedPrompt.substring(0, 4000) + "…";
        }
        int chars = ctx.texts == null ? 0 : ctx.texts.stream().mapToInt(t -> t.text().length()).sum();
        audit.publish(new AuditEvent(ctx.requestId, ctx.key.orgId(), ctx.key.keyId(), ctx.key.teamId(), ctx.key.teamName(),
                ctx.actor, "api", ctx.kind, null, ctx.model, ctx.servedBy,
                ctx.decision.action().name(), status, reasons, findings, redactedPrompt, chars, ctx.tokensIn, ctx.tokensOut,
                ctx.costMicros, ctx.elapsedMs(), ctx.scan == null ? 0 : ctx.scan.micros(), ctx.upstreamMs, ctx.cacheHit,
                ctx.stream, policy == null ? 0 : policy.version(), ctx.at));
    }
}
