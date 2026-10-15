package dev.veil.client;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Client for Veil's Scan API. Use it to check text before you store it, log it or send it anywhere:
 *
 * <pre>{@code
 * VeilClient veil = VeilClient.builder("https://veil.example.com", System.getenv("VEIL_KEY")).build();
 * VeilClient.ScanResult r = veil.scan(ticket.body());
 * if (r.blocked()) throw new IllegalStateException(String.join("; ", r.reasons()));
 * log.info("Ticket: {}", r.redactedText());
 * }</pre>
 *
 * For chat completions, use any OpenAI SDK with its base URL set to {@code https://<veil>/v1}.
 */
public final class VeilClient {

    public record Finding(String type, String label, String category, String severity, String detail, double confidence,
                         int start, int end, String action, String rule) {
    }

    public record ScanResult(String requestId, String action, List<String> reasons, List<Finding> findings, String redactedText,
                             int policyVersion) {
        public boolean blocked() {
            return "BLOCK".equals(action);
        }

        public boolean clean() {
            return findings.isEmpty();
        }
    }

    /** Thrown for HTTP errors; {@code status} 401 means a bad key, 429 a rate limit. */
    public static final class VeilException extends RuntimeException {
        private final int status;

        VeilException(int status, String body) {
            super("Veil returned HTTP " + status + ": " + body);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    private final HttpClient http;
    private final URI base;
    private final String key;
    private final Duration timeout;

    private VeilClient(Builder b) {
        this.base = URI.create(b.baseUrl.replaceAll("/+$", "") + "/");
        this.key = b.key;
        this.timeout = b.timeout;
        this.http = HttpClient.newBuilder().connectTimeout(b.timeout).build();
    }

    public static Builder builder(String baseUrl, String virtualKey) {
        return new Builder(baseUrl, virtualKey);
    }

    public ScanResult scan(String text) {
        Objects.requireNonNull(text, "text");
        HttpRequest req = HttpRequest.newBuilder(base.resolve("v1/scan")).timeout(timeout)
                .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("text", text)))).build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) throw new VeilException(res.statusCode(), res.body());
            return JSON.readValue(res.body(), ScanResult.class);
        } catch (IOException e) {
            throw new VeilException(0, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VeilException(0, "interrupted");
        }
    }

    /** The text with every value the policy redacts replaced by a placeholder; throws if the policy blocks it. */
    public String redact(String text) {
        ScanResult r = scan(text);
        if (r.blocked()) throw new VeilException(403, String.join("; ", r.reasons()));
        return r.redactedText();
    }

    public static final class Builder {
        private final String baseUrl;
        private final String key;
        private Duration timeout = Duration.ofSeconds(10);

        private Builder(String baseUrl, String key) {
            this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
            this.key = Objects.requireNonNull(key, "virtualKey");
        }

        public Builder timeout(Duration t) {
            this.timeout = t;
            return this;
        }

        public VeilClient build() {
            return new VeilClient(this);
        }
    }
}
