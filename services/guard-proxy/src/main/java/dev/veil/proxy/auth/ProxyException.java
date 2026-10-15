package dev.veil.proxy.auth;

import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/**
 * An error returned in OpenAI's error format, so client SDKs surface it naturally:
 * {@code {"error": {"message", "type", "code", "param"}}}, plus Veil details when useful.
 */
public class ProxyException extends RuntimeException {

    private final HttpStatus status;
    private final String type;
    private final String code;
    private final Map<String, Object> details;
    private final long retryAfterSeconds;

    public ProxyException(HttpStatus status, String type, String code, String message) {
        this(status, type, code, message, Map.of(), 0);
    }

    public ProxyException(HttpStatus status, String type, String code, String message, Map<String, Object> details, long retryAfterSeconds) {
        super(message);
        this.status = status;
        this.type = type;
        this.code = code;
        this.details = details;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static ProxyException unauthorized(String message) {
        return new ProxyException(HttpStatus.UNAUTHORIZED, "invalid_request_error", "invalid_api_key", message);
    }

    public static ProxyException badRequest(String message) {
        return new ProxyException(HttpStatus.BAD_REQUEST, "invalid_request_error", "invalid_request", message);
    }

    public static ProxyException blocked(String message, List<String> reasons, List<String> types, String requestId) {
        return new ProxyException(HttpStatus.FORBIDDEN, "veil_blocked", "policy_violation", message,
                Map.of("reasons", reasons, "detected", types, "request_id", requestId), 0);
    }

    public HttpStatus status() {
        return status;
    }

    public String type() {
        return type;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
