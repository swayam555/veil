package dev.veil.detect;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds sensitive material that has no pattern: unreleased financials, an acquisition, an HR case, patient
 * health information, or a paraphrased prompt injection. The text is split into passages, each passage is
 * embedded, and a k-nearest-neighbour vote over labelled examples decides. "Neutral" examples act as the
 * counterweight, so "explain SQL injection in general" doesn't look like "we have an SQL injection in prod".
 *
 * <p>Adding a topic is adding example sentences: no training run, no GPU, no data leaves the process.
 */
public final class SemanticDetector implements Detector {

    public record Example(String label, String text) {
    }

    public record Thresholds(double minSimilarity, double minMargin) {
        public static final Thresholds DEFAULT = new Thresholds(0.42, 0.15);
    }

    private record Embedded(String label, float[] vector) {
    }

    private static final int SHORT_TEXT = 400;
    private static final int MIN_PASSAGE = 80;
    private static final int MAX_PASSAGE = 400;
    private static final int MAX_PASSAGES = 16;
    private static final String NEUTRAL = "neutral";
    public static final String INJECTION = "injection";

    private final TextEmbedder embedder;
    private final List<Embedded> examples;
    private final Map<String, String> labels;
    private final Thresholds thresholds;

    public SemanticDetector(TextEmbedder embedder, List<Example> examples, Map<String, String> labels, Thresholds thresholds) {
        this.embedder = embedder;
        this.labels = Map.copyOf(labels);
        this.thresholds = thresholds;
        List<Embedded> list = new ArrayList<>(examples.size());
        for (Example e : examples) list.add(new Embedded(e.label(), embedder.embed(e.text())));
        this.examples = List.copyOf(list);
    }

    /** The built-in topics plus any organisation-specific examples. */
    public static SemanticDetector withDefaults(TextEmbedder embedder, List<Example> extra, Map<String, String> extraLabels) {
        Defaults d = Defaults.load();
        List<Example> all = new ArrayList<>(d.examples());
        all.addAll(extra);
        Map<String, String> labels = new HashMap<>(d.labels());
        labels.putAll(extraLabels);
        return new SemanticDetector(embedder, all, labels, Thresholds.DEFAULT);
    }

    @Override
    public String name() {
        return "semantic";
    }

    public int exampleCount() {
        return examples.size();
    }

    @Override
    public List<Finding> detect(String text) {
        Map<String, Finding> best = new LinkedHashMap<>();
        for (int[] p : passages(text)) {
            String passage = text.substring(p[0], p[1]);
            if (passage.isBlank()) continue;
            Score s = classify(passage);
            if (s == null) continue;
            EntityType type = INJECTION.equals(s.label()) ? EntityType.PROMPT_INJECTION : EntityType.CONFIDENTIAL_TOPIC;
            String excerpt = passage.length() > 120 ? passage.substring(0, 120) + "…" : passage;
            Finding f = new Finding(type, p[0], p[1], excerpt.strip(), s.confidence(), name(),
                    labels.getOrDefault(s.label(), s.label()));
            best.merge(s.label(), f, (a, b) -> a.confidence() >= b.confidence() ? a : b);
        }
        return new ArrayList<>(best.values());
    }

    record Score(String label, double similarity, double margin, double confidence) {
    }

    /** Scores one passage. Public for the explainability view and tests. */
    public Map<String, Double> labelScores(String passage) {
        float[] v = embedder.embed(passage);
        Map<String, List<Double>> sims = new HashMap<>();
        for (Embedded e : examples) {
            sims.computeIfAbsent(e.label(), k -> new ArrayList<>()).add(TextEmbedder.cosine(v, e.vector()));
        }
        Map<String, Double> scores = new HashMap<>();
        // Average of the two closest examples per label: one lucky match isn't enough.
        sims.forEach((label, list) -> {
            list.sort((a, b) -> Double.compare(b, a));
            double s = list.size() == 1 ? list.getFirst() : (list.get(0) + list.get(1)) / 2;
            scores.put(label, s);
        });
        return scores;
    }

    private Score classify(String passage) {
        Map<String, Double> scores = labelScores(passage);
        double neutral = scores.getOrDefault(NEUTRAL, 0.0);
        String bestLabel = null;
        double bestScore = -1;
        for (var e : scores.entrySet()) {
            if (e.getKey().equals(NEUTRAL)) continue;
            if (e.getValue() > bestScore) {
                bestScore = e.getValue();
                bestLabel = e.getKey();
            }
        }
        double margin = bestScore - neutral;
        if (bestLabel == null || bestScore < thresholds.minSimilarity() || margin < thresholds.minMargin()) return null;
        double confidence = Math.min(0.97, 0.55 + (bestScore - thresholds.minSimilarity()) * 1.2 + margin * 1.5);
        return new Score(bestLabel, bestScore, margin, confidence);
    }

    /**
     * Short texts are one passage, so the whole request is judged in context. Longer texts are split into
     * sentences (short ones merged), so one confidential sentence isn't diluted by pages of harmless text.
     * Very long documents are sampled evenly to bound the cost.
     */
    static List<int[]> passages(String text) {
        List<int[]> out = new ArrayList<>();
        if (text.length() <= SHORT_TEXT) {
            out.add(new int[]{0, text.length()});
            return out;
        }
        int start = 0;
        int i = 0;
        while (i < text.length()) {
            char ch = text.charAt(i);
            boolean boundary = ch == '.' || ch == '!' || ch == '?' || ch == '\n';
            int len = i + 1 - start;
            if ((boundary && len >= MIN_PASSAGE) || len >= MAX_PASSAGE) {
                out.add(new int[]{start, i + 1});
                start = i + 1;
            }
            i++;
        }
        if (start < text.length()) {
            if (text.length() - start < MIN_PASSAGE && !out.isEmpty()) out.getLast()[1] = text.length();
            else out.add(new int[]{start, text.length()});
        }
        if (out.size() <= MAX_PASSAGES) return out;
        List<int[]> sampled = new ArrayList<>(MAX_PASSAGES);
        double step = (double) out.size() / MAX_PASSAGES;
        for (int k = 0; k < MAX_PASSAGES; k++) sampled.add(out.get((int) (k * step)));
        return sampled;
    }

    public record Defaults(List<Example> examples, Map<String, String> labels) {

        private static volatile Defaults cached;

        public static Defaults load() {
            Defaults d = cached;
            if (d != null) return d;
            List<Example> examples = new ArrayList<>();
            Map<String, String> labels = new LinkedHashMap<>();
            try (InputStream in = SemanticDetector.class.getResourceAsStream("/veil/semantic-exemplars.txt")) {
                if (in == null) throw new IllegalStateException("semantic-exemplars.txt missing");
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.strip();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    if (line.startsWith("@")) {
                        int eq = line.indexOf('=');
                        labels.put(line.substring(1, eq), line.substring(eq + 1));
                        continue;
                    }
                    int bar = line.indexOf('|');
                    examples.add(new Example(line.substring(0, bar), line.substring(bar + 1)));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            d = new Defaults(List.copyOf(examples), Map.copyOf(labels));
            cached = d;
            return d;
        }
    }
}
