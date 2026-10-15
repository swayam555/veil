package dev.veil.control.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param masterKey    base64 AES-256 key that encrypts provider API keys; shared with the proxy
 * @param proxyUrl     guard-proxy base URL, used by the traffic simulator
 * @param seedDemo     create the demo organisation, users, keys and policy on first start
 * @param upstream     optional real LLM provider added to the demo organisation
 */
@ConfigurationProperties("veil")
public record AppProperties(String jwtSecret, String masterKey, String proxyUrl, boolean seedDemo, Ai ai, Upstream upstream) {

    /** Model used by the policy copilot. provider: none | openai | anthropic | ollama. */
    public record Ai(String provider, String apiKey, String baseUrl, String model, Duration timeout) {
    }

    /** A real OpenAI-compatible provider for the proxy (OpenAI, Groq, Gemini, OpenRouter, Ollama...). */
    public record Upstream(String baseUrl, String apiKey, String model) {
    }
}
