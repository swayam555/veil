package dev.veil.control.ai;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.veil.control.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/** The copilot's LLM, if one is configured. Everything works, more simply, without one. */
@Component
public class ChatModels {

    private static final Logger log = LoggerFactory.getLogger(ChatModels.class);

    private final ChatModel model;
    private final String description;

    public ChatModels(AppProperties props) {
        AppProperties.Ai ai = props.ai();
        String provider = ai == null || ai.provider() == null ? "none" : ai.provider().trim().toLowerCase();
        Duration timeout = ai == null || ai.timeout() == null ? Duration.ofSeconds(60) : ai.timeout();
        ChatModel built = null;
        String desc = "none";
        try {
            switch (provider) {
                case "openai" -> {
                    String m = or(ai.model(), "gpt-4o-mini");
                    built = OpenAiChatModel.builder().baseUrl(or(ai.baseUrl(), "https://api.openai.com/v1"))
                            .apiKey(ai.apiKey()).modelName(m).temperature(0.0).timeout(timeout).maxRetries(1).build();
                    desc = "openai-compatible:" + m;
                }
                case "anthropic" -> {
                    String m = or(ai.model(), "claude-opus-5-5");
                    // Current Claude models reject sampling parameters, so none are set.
                    built = AnthropicChatModel.builder().apiKey(ai.apiKey()).modelName(m).timeout(timeout)
                            .maxTokens(16000).maxRetries(1).build();
                    desc = "anthropic:" + m;
                }
                case "ollama" -> {
                    String m = or(ai.model(), "qwen2.5:1.5b");
                    built = OllamaChatModel.builder().baseUrl(or(ai.baseUrl(), "http://localhost:11434")).modelName(m)
                            .temperature(0.0).timeout(timeout).maxRetries(1).build();
                    desc = "ollama:" + m;
                }
                default -> { }
            }
        } catch (RuntimeException e) {
            log.error("Could not configure AI provider '{}'; using the rule-based copilot", provider, e);
            built = null;
            desc = "none";
        }
        this.model = built;
        this.description = desc;
        log.info("Policy copilot model: {}", description);
    }

    public Optional<ChatModel> model() {
        return Optional.ofNullable(model);
    }

    public String description() {
        return description;
    }

    private static String or(String v, String d) {
        return v == null || v.isBlank() ? d : v;
    }
}
