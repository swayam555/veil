package dev.veil.detect;

/**
 * Rehydrates a streamed answer chunk by chunk. A placeholder can be split across chunks ({@code "[EMA"} then
 * {@code "IL_1] is"}), so the rehydrator holds back only a tail that could still become a placeholder and
 * releases everything else immediately. The user sees the stream with at most a few characters of delay.
 */
public final class StreamingRehydrator {

    /** Longest placeholder we ever produce, e.g. "[CONFIDENTIAL_TOPIC_9999]". */
    private static final int MAX_PLACEHOLDER = 32;

    private final RedactionVault vault;
    private final StringBuilder pending = new StringBuilder();

    StreamingRehydrator(RedactionVault vault) {
        this.vault = vault;
    }

    /** Accepts the next chunk and returns the text that is safe to emit now (possibly empty). */
    public String feed(String chunk) {
        if (chunk == null || chunk.isEmpty()) return "";
        pending.append(chunk);
        int hold = holdFrom(pending);
        String ready = pending.substring(0, hold);
        pending.delete(0, hold);
        return vault.rehydrate(ready);
    }

    /** Emits whatever is left at the end of the stream. */
    public String flush() {
        String rest = vault.rehydrate(pending.toString());
        pending.setLength(0);
        return rest;
    }

    /** Index from which the buffer might still be the start of a placeholder; buffer length if none. */
    static int holdFrom(CharSequence buf) {
        int open = lastIndexOf(buf, '[');
        if (open < 0) return buf.length();
        // A complete "[...]" after the last '[' means nothing is pending.
        for (int i = open + 1; i < buf.length(); i++) {
            if (buf.charAt(i) == ']') return buf.length();
        }
        int tailLength = buf.length() - open;
        if (tailLength > MAX_PLACEHOLDER) return buf.length();
        for (int i = open + 1; i < buf.length(); i++) {
            char c = buf.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || c == '_' || (c >= '0' && c <= '9');
            if (!ok) return buf.length();
        }
        return open;
    }

    private static int lastIndexOf(CharSequence s, char c) {
        for (int i = s.length() - 1; i >= 0; i--) {
            if (s.charAt(i) == c) return i;
        }
        return -1;
    }
}
