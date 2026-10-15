package dev.veil.detect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reversible redaction for one request. The same value always gets the same placeholder ({@code [EMAIL_1]}),
 * across every message in the request, so the model can still reason about "the same customer". The vault
 * lives only in memory for the lifetime of the request and is never logged or stored.
 */
public final class RedactionVault {

    public static final Pattern PLACEHOLDER = Pattern.compile("\\[([A-Z][A-Z_]*?)_(\\d{1,4})]");

    private final Map<String, String> byValue = new HashMap<>();
    private final Map<String, String> byPlaceholder = new HashMap<>();
    private final Map<EntityType, Integer> counters = new EnumMap<>(EntityType.class);

    public synchronized String placeholderFor(EntityType type, String value) {
        String key = type.name() + "\u0000" + normalise(type, value);
        return byValue.computeIfAbsent(key, k -> {
            int n = counters.merge(type, 1, Integer::sum);
            String p = "[" + type.placeholderStem() + "_" + n + "]";
            byPlaceholder.put(p, value);
            return p;
        });
    }

    private static String normalise(EntityType type, String value) {
        String v = value.strip();
        return switch (type) {
            case EMAIL, CUSTOM_TERM, PERSON -> v.toLowerCase(Locale.ROOT);
            case PHONE, AADHAAR, CREDIT_CARD, US_SSN, BANK_ACCOUNT -> Checksums.digitsOnly(v);
            case IBAN -> v.replace(" ", "").toUpperCase(Locale.ROOT);
            default -> v;
        };
    }

    /** Replaces each finding's span with its placeholder. Findings must not overlap (ScanEngine guarantees it). */
    public String redact(String text, List<Finding> findings) {
        if (findings.isEmpty()) return text;
        List<Finding> sorted = new ArrayList<>(findings);
        sorted.removeIf(f -> !f.type().spanBased());
        sorted.sort(Comparator.comparingInt(Finding::start));
        StringBuilder out = new StringBuilder(text.length());
        int pos = 0;
        for (Finding f : sorted) {
            if (f.start() < pos || f.end() > text.length()) continue;
            out.append(text, pos, f.start()).append(placeholderFor(f.type(), text.substring(f.start(), f.end())));
            pos = f.end();
        }
        return out.append(text.substring(pos)).toString();
    }

    /** Puts the original values back. Unknown placeholders (the model invented one) are left as they are. */
    public String rehydrate(String text) {
        if (text == null || byPlaceholder.isEmpty() || text.indexOf('[') < 0) return text;
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (m.find()) {
            String original = byPlaceholder.get(m.group());
            m.appendReplacement(out, Matcher.quoteReplacement(original != null ? original : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    public synchronized int size() {
        return byPlaceholder.size();
    }

    public synchronized Map<String, String> placeholders() {
        return Collections.unmodifiableMap(new HashMap<>(byPlaceholder));
    }

    public StreamingRehydrator streaming() {
        return new StreamingRehydrator(this);
    }
}
