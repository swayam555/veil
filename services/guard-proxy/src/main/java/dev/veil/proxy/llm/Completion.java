package dev.veil.proxy.llm;

import tools.jackson.databind.node.ObjectNode;

/** A complete OpenAI-format chat completion plus token usage (estimated when the provider doesn't report it). */
public record Completion(ObjectNode response, int tokensIn, int tokensOut) {
}
