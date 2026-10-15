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
import tools.jackson.databind.node.ObjectNode;

/**
 * OpenAI's API and everything that speaks it: OpenAI, Azure OpenAI (via a compatible gateway), Groq, Google Gemini's
 * OpenAI endpoint, OpenRouter, Together, vLLM and Ollama. The request is passed through unchanged apart from the
 * model name, so tools, JSON mode and other parameters keep working.
 */
@Component
public class OpenAiCompatibleProvider implements LlmProvider {

    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE = new ParameterizedTypeReference<>() {
    };

    private final WebClient web;
    private final JsonCodec json;

    public OpenAiCompatibleProvider(WebClient.Builder builder, JsonCodec json) {
        this.web = builder.codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024)).build();
        this.json = json;
    }

    @Override
    public OrgConfig.ProviderKind kind() {
        return OrgConfig.ProviderKind.OPENAI_COMPATIBLE;
    }

    @Override
    public Mono<Completion> complete(ProviderCall call) {
        ObjectNode body = call.body().deepCopy();
        body.put("model", call.upstreamModel());
        body.put("stream", false);
        return request(call, body).retrieve()
                .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class).defaultIfEmpty("")
                        .map(b -> UpstreamException.fromStatus(r.statusCode().value(), b)))
                .bodyToMono(String.class)
                .map(s -> {
                    ObjectNode r = (ObjectNode) json.tree(s);
                    JsonNode u = r.path("usage");
                    int in = u.path("prompt_tokens").asInt(Payloads.promptTokens(call.body()));
                    int out = u.path("completion_tokens").asInt(Payloads.estimateTokens(r.path("choices").path(0).path("message").path("content").asString("")));
                    return new Completion(r, in, out);
                });
    }

    @Override
    public Flux<ObjectNode> stream(ProviderCall call) {
        ObjectNode body = call.body().deepCopy();
        body.put("model", call.upstreamModel());
        body.put("stream", true);
        return request(call, body).accept(MediaType.TEXT_EVENT_STREAM).retrieve()
                .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class).defaultIfEmpty("")
                        .map(b -> UpstreamException.fromStatus(r.statusCode().value(), b)))
                .bodyToFlux(SSE)
                .mapNotNull(ServerSentEvent::data)
                .takeWhile(d -> !"[DONE]".equals(d.trim()))
                .filter(d -> !d.isBlank())
                .map(d -> (ObjectNode) json.tree(d));
    }

    private WebClient.RequestHeadersSpec<?> request(ProviderCall call, ObjectNode body) {
        String base = call.provider().baseUrl().replaceAll("/+$", "");
        WebClient.RequestBodySpec spec = web.post().uri(base + "/chat/completions").contentType(MediaType.APPLICATION_JSON);
        if (call.apiKey() != null) spec = spec.header("Authorization", "Bearer " + call.apiKey());
        return spec.bodyValue(json.write(body));
    }
}
