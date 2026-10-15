package dev.veil.proxy.config;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class JsonCodec {

    private final JsonMapper mapper;

    public JsonCodec(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public JsonMapper mapper() {
        return mapper;
    }

    public String write(Object value) {
        return mapper.writeValueAsString(value);
    }

    public <T> T read(String json, Class<T> type) {
        return mapper.readValue(json, type);
    }

    public JsonNode tree(String json) {
        return mapper.readTree(json);
    }

    public ObjectNode object() {
        return mapper.createObjectNode();
    }
}
