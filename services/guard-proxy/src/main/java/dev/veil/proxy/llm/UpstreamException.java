package dev.veil.proxy.llm;

/**
 * A provider failed. {@code retryable} failures (network, timeout, 5xx, 429, bad provider credentials) move on to
 * the next target in the route; others (the request itself is invalid) go back to the client.
 */
public class UpstreamException extends RuntimeException {

    private final int status;
    private final boolean retryable;

    public UpstreamException(String message, int status, boolean retryable) {
        super(message);
        this.status = status;
        this.retryable = retryable;
    }

    public static UpstreamException fromStatus(int status, String body) {
        boolean retryable = status >= 500 || status == 429 || status == 401 || status == 403 || status == 408;
        String snippet = body == null ? "" : body.length() > 300 ? body.substring(0, 300) : body;
        return new UpstreamException("Provider returned HTTP " + status + (snippet.isBlank() ? "" : ": " + snippet), status, retryable);
    }

    public int status() {
        return status;
    }

    public boolean retryable() {
        return retryable;
    }
}
