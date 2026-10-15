package dev.veil.proxy.llm;

import dev.veil.contracts.OrgConfig;
import tools.jackson.databind.node.ObjectNode;

/**
 * One attempt against one provider. {@code body} is an OpenAI-format chat request that has already been
 * redacted, with {@code model} set to the provider's own model name.
 */
public record ProviderCall(OrgConfig.ProviderConfig provider, String apiKey, String upstreamModel, ObjectNode body) {
}
