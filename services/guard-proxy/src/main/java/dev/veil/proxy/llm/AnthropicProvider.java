package dev.veil.proxy.llm;

import dev.veil.contracts.OrgConfig;
import dev.veil.proxy.config.JsonCodec;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Anthropic's Messages API, translated to and from OpenAI's format so clients don't need to care. System messages
 * become the {@code system} field; streamed {@code content_block_delta} events become OpenAI chunks. Sampling
 * parameters are not forwarded because current Claude models choose them themselves.
 */
@Component
public class AnthropicProvider implements LlmProvider {

    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE = new ParameterizedTypeReference<>() {
    };
    private static final String DEFAULT_URL = "https://api.anthropic.com";

    private final WebClient web;
    private final JsonCodec json;

    public AnthropicProvider(WebClient.Builder builder, JsonCodec json) {
        this.web = builder.codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024)).build();
        this.json = json;
    }

    @Override
    public OrgConfig.ProviderKind kind() {
        return OrgConfig.ProviderKind.ANTHROPIC;
    }

    @Override
    public Mono<Completion> complete(ProviderCall call) {
        ObjectNode req = translate(call, false);
        return post(call, req).retrieve()
                .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class).defaultIfEmpty("")
                        .map(b -> UpstreamException.fromStatus(r.statusCode().value(), b)))
                .bodyToMono(String.class)
                .map(s -> {
                    JsonNode r = json.tree(s);
                    StringBuilder text = new StringBuilder();
                    for (JsonNode block : r.path("content").values()) {
                        if ("text".equals(block.path("type").asString(""))) text.append(block.path("text").asString(""));
                    }
                    int in = r.path("usage").path("input_tokens").asInt(0);
                    int out = r.path("usage").path("output_tokens").asInt(0);
                    String finish = "max_tokens".equals(r.path("stop_reason").asString("")) ? "length" : "stop";
                    return new Completion(Payloads.completion(r.path("id").asString(Payloads.newId()), call.upstreamModel(),
                            text.toString(), finish, in, out), in, out);
                });
    }

    @Override
    public Flux<ObjectNode> stream(ProviderCall call) {
        ObjectNode req = translate(call, true);
        String id = Payloads.newId();
        AtomicBoolean first = new AtomicBoolean(true);
        AtomicInteger in = new AtomicInteger();
        AtomicInteger out = new AtomicInteger();
        return post(call, req).accept(MediaType.TEXT_EVENT_STREAM).retrieve()
                .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class).defaultIfEmpty("")
                        .map(b -> UpstreamException.fromStatus(r.statusCode().value(), b)))
                .bodyToFlux(SSE)
                .mapNotNull(ServerSentEvent::data)
                .concatMap(d -> {
                    JsonNode e = json.tree(d);
                    switch (e.path("type").asString("")) {
                        case "message_start" -> in.set(e.path("message").path("usage").path("input_tokens").asInt(0));
                        case "content_block_delta" -> {
                            String t = e.path("delta").path("text").asString("");
                            if (!t.isEmpty()) return Flux.just(Payloads.chunk(id, call.upstreamModel(), t, null, first.getAndSet(false)));
                        }
                        case "message_delta" -> out.set(e.path("usage").path("output_tokens").asInt(out.get()));
                        case "message_stop" -> {
                            return Flux.just(Payloads.chunk(id, call.upstreamModel(), null, "stop", false),
                                    Payloads.usageChunk(id, call.upstreamModel(), in.get(), out.get()));
                        }
                        case "error" -> {
                            return Flux.error(new UpstreamException("Anthropic stream error: " + e.path("error").path("message").asString(""), 502, true));
                        }
                        default -> { }
                    }
                    return Flux.empty();
                });
    }

    private WebClient.RequestHeadersSpec<?> post(ProviderCall call, ObjectNode req) {
        String base = call.provider().baseUrl() == null || call.provider().baseUrl().isBlank() ? DEFAULT_URL
                : call.provider().baseUrl().replaceAll("/+$", "");
        return web.post().uri(base + "/v1/messages").contentType(MediaType.APPLICATION_JSON)
                .header("x-api-key", call.apiKey() == null ? "" : call.apiKey())
                .header("anthropic-version", "2023-06-01")
                .bodyValue(json.write(req));
    }

    ObjectNode translate(ProviderCall call, boolean stream) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        ObjectNode req = f.objectNode();
        req.put("model", call.upstreamModel());
        int max = call.body().path("max_tokens").asInt(call.body().path("max_completion_tokens").asInt(1024));
        req.put("max_tokens", Math.max(1, max));
        if (stream) req.put("stream", true);
        StringBuilder system = new StringBuilder();
        ArrayNode messages = req.putArray("messages");
        for (JsonNode m : call.body().path("messages").values()) {
            String role = m.path("role").asString("user");
            String text = Payloads.contentText(m);
            if (role.equals("system") || role.equals("developer")) {
                if (!system.isEmpty()) system.append("\n\n");
                system.append(text);
                continue;
            }
            ObjectNode out = messages.addObject();
            out.put("role", role.equals("assistant") ? "assistant" : "user");
            out.put("content", text);
        }
        if (!system.isEmpty()) req.put("system", system.toString());
        return req;
    }
}
