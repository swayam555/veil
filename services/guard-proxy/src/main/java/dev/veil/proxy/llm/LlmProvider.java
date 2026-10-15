package dev.veil.proxy.llm;

import dev.veil.contracts.OrgConfig;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.node.ObjectNode;

/** Talks to one kind of upstream. Every provider speaks OpenAI's format to the rest of the proxy. */
public interface LlmProvider {

    OrgConfig.ProviderKind kind();

    Mono<Completion> complete(ProviderCall call);

    /** OpenAI-format {@code chat.completion.chunk} objects; the last one may carry {@code usage}. */
    Flux<ObjectNode> stream(ProviderCall call);
}
