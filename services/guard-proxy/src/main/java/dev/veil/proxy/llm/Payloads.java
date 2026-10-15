package dev.veil.proxy.llm;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.UUID;

/** Builders for OpenAI-format responses and helpers to read chat requests. */
public final class Payloads {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private Payloads() {
    }

    public static String newId() {
        return "chatcmpl-veil-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    public static ObjectNode completion(String id, String model, String content, String finishReason, int in, int out) {
        ObjectNode r = F.objectNode();
        r.put("id", id);
        r.put("object", "chat.completion");
        r.put("created", Instant.now().getEpochSecond());
        r.put("model", model);
        ArrayNode choices = r.putArray("choices");
        ObjectNode choice = choices.addObject();
        choice.put("index", 0);
        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");
        message.put("content", content);
        choice.put("finish_reason", finishReason);
        usage(r, in, out);
        return r;
    }

    public static ObjectNode chunk(String id, String model, String content, String finishReason, boolean first) {
        ObjectNode r = F.objectNode();
        r.put("id", id);
        r.put("object", "chat.completion.chunk");
        r.put("created", Instant.now().getEpochSecond());
        r.put("model", model);
        ArrayNode choices = r.putArray("choices");
        ObjectNode choice = choices.addObject();
        choice.put("index", 0);
        ObjectNode delta = choice.putObject("delta");
        if (first) delta.put("role", "assistant");
        if (content != null) delta.put("content", content);
        if (finishReason == null) choice.putNull("finish_reason");
        else choice.put("finish_reason", finishReason);
        return r;
    }

    public static ObjectNode usageChunk(String id, String model, int in, int out) {
        ObjectNode r = F.objectNode();
        r.put("id", id);
        r.put("object", "chat.completion.chunk");
        r.put("created", Instant.now().getEpochSecond());
        r.put("model", model);
        r.putArray("choices");
        usage(r, in, out);
        return r;
    }

    public static void usage(ObjectNode r, int in, int out) {
        ObjectNode u = r.putObject("usage");
        u.put("prompt_tokens", in);
        u.put("completion_tokens", out);
        u.put("total_tokens", in + out);
    }

    /** Rough token estimate (about 4 characters per token for English) for providers that don't report usage. */
    public static int estimateTokens(String text) {
        return text == null || text.isEmpty() ? 0 : Math.max(1, text.length() / 4);
    }

    /** All text in a message's content, whether a string or an array of parts. */
    public static String contentText(JsonNode message) {
        JsonNode c = message.path("content");
        if (c.isString()) return c.asString();
        if (c.isArray()) {
            StringBuilder b = new StringBuilder();
            for (JsonNode part : c.values()) {
                if ("text".equals(part.path("type").asString("")) && part.has("text")) {
                    if (!b.isEmpty()) b.append('\n');
                    b.append(part.path("text").asString(""));
                }
            }
            return b.toString();
        }
        return "";
    }

    public static int promptTokens(JsonNode body) {
        int n = 0;
        for (JsonNode m : body.path("messages").values()) n += estimateTokens(contentText(m)) + 4;
        return n;
    }
}
