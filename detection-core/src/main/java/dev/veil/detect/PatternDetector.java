package dev.veil.detect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A regex candidate generator followed by a validator (usually a checksum) and context scoring: nearby words
 * such as "Aadhaar" or "card no" raise confidence, and some detectors only fire when such words are present.
 */
public final class PatternDetector implements Detector {

    private static final int CONTEXT_BEFORE = 48;
    private static final int CONTEXT_AFTER = 16;

    private final String name;
    private final EntityType type;
    private final Pattern pattern;
    private final int group;
    private final Predicate<String> validator;
    private final double baseConfidence;
    private final double contextConfidence;
    private final List<String> contextWords;
    private final boolean requireContext;
    private final double penaltyConfidence;
    private final List<String> penaltyWords;
    private final String detail;

    private PatternDetector(Builder b) {
        this.name = b.name;
        this.type = b.type;
        this.pattern = b.pattern;
        this.group = b.group;
        this.validator = b.validator;
        this.baseConfidence = b.baseConfidence;
        this.contextConfidence = b.contextConfidence;
        this.contextWords = b.contextWords;
        this.requireContext = b.requireContext;
        this.penaltyConfidence = b.penaltyConfidence;
        this.penaltyWords = b.penaltyWords;
        this.detail = b.detail;
    }

    public static Builder builder(String name, EntityType type, String regex) {
        return new Builder(name, type, Pattern.compile(regex));
    }

    public static Builder builder(String name, EntityType type, Pattern pattern) {
        return new Builder(name, type, pattern);
    }

    @Override
    public String name() {
        return "pattern:" + name;
    }

    public EntityType type() {
        return type;
    }

    @Override
    public List<Finding> detect(String text) {
        List<Finding> out = new ArrayList<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            int s = m.start(group);
            int e = m.end(group);
            if (s < 0) continue;
            String value = text.substring(s, e);
            if (!validator.test(value)) continue;
            boolean context = hasContext(text, s, e, contextWords);
            if (requireContext && !context) continue;
            double confidence = context ? contextConfidence : baseConfidence;
            if (!context && hasContext(text, s, e, penaltyWords)) confidence = penaltyConfidence;
            out.add(new Finding(type, s, e, value, confidence, name(), detail));
        }
        return out;
    }

    private static boolean hasContext(String text, int s, int e, List<String> words) {
        if (words.isEmpty()) return false;
        String window = text.substring(Math.max(0, s - CONTEXT_BEFORE), s).toLowerCase(Locale.ROOT)
                + " " + text.substring(e, Math.min(text.length(), e + CONTEXT_AFTER)).toLowerCase(Locale.ROOT);
        for (String w : words) {
            if (window.contains(w)) return true;
        }
        return false;
    }

    public static final class Builder {
        private final String name;
        private final EntityType type;
        private final Pattern pattern;
        private int group = 0;
        private Predicate<String> validator = v -> true;
        private double baseConfidence = 0.9;
        private double contextConfidence = -1;
        private List<String> contextWords = List.of();
        private boolean requireContext;
        private double penaltyConfidence;
        private List<String> penaltyWords = List.of();
        private String detail;

        private Builder(String name, EntityType type, Pattern pattern) {
            this.name = name;
            this.type = type;
            this.pattern = pattern;
            this.detail = type.label();
        }

        public Builder group(int g) {
            this.group = g;
            return this;
        }

        public Builder validate(Predicate<String> v) {
            this.validator = v;
            return this;
        }

        public Builder confidence(double base) {
            this.baseConfidence = base;
            return this;
        }

        /** Words that raise confidence to {@code boosted} when found just before or after the match. */
        public Builder context(double boosted, String... words) {
            this.contextConfidence = boosted;
            this.contextWords = List.of(words);
            return this;
        }

        /** Words that point to a different kind of number; without positive context, confidence drops to {@code lowered}. */
        public Builder penalty(double lowered, String... words) {
            this.penaltyConfidence = lowered;
            this.penaltyWords = List.of(words);
            return this;
        }

        public Builder requireContext() {
            this.requireContext = true;
            return this;
        }

        public Builder detail(String d) {
            this.detail = d;
            return this;
        }

        public PatternDetector build() {
            if (contextConfidence < 0) contextConfidence = baseConfidence;
            return new PatternDetector(this);
        }
    }
}
