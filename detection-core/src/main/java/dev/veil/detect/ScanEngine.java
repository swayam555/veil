package dev.veil.detect;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs every detector over a text and resolves overlaps, so each character belongs to at most one span finding.
 * Document-level findings (source code, confidential topic, prompt injection) are kept alongside span findings.
 */
public final class ScanEngine {

    /** Findings below this are noise for every policy; policies apply their own, higher thresholds. */
    public static final double FLOOR = 0.5;

    private static final Comparator<Finding> PRIORITY = Comparator
            .comparing((Finding f) -> f.type().severity()).reversed()
            .thenComparing(Comparator.comparingInt(Finding::length).reversed())
            .thenComparing(Comparator.comparingDouble(Finding::confidence).reversed());

    private final List<Detector> detectors;

    public ScanEngine(List<Detector> detectors) {
        this.detectors = List.copyOf(detectors);
    }

    /** Pattern, name, source-code and injection-heuristic detectors: everything that needs no model. */
    public static List<Detector> standardDetectors() {
        List<Detector> list = new ArrayList<>(PatternLibrary.all());
        list.add(new NameDetector());
        list.add(new SourceCodeDetector());
        list.add(new InjectionHeuristics());
        return list;
    }

    public static ScanEngine standard() {
        return new ScanEngine(standardDetectors());
    }

    /** A new engine with extra detectors (an organisation's dictionary, the semantic detector). */
    public ScanEngine with(Collection<? extends Detector> extra) {
        List<Detector> list = new ArrayList<>(detectors);
        list.addAll(extra);
        return new ScanEngine(list);
    }

    public List<String> detectorNames() {
        return detectors.stream().map(Detector::name).toList();
    }

    public ScanResult scan(String text) {
        if (text == null || text.isEmpty()) return new ScanResult(List.of(), Map.of(), 0);
        long t0 = System.nanoTime();
        List<Finding> raw = new ArrayList<>();
        Map<String, Long> micros = new LinkedHashMap<>();
        for (Detector d : detectors) {
            long s = System.nanoTime();
            for (Finding f : d.detect(text)) {
                if (f.confidence() >= FLOOR) raw.add(f);
            }
            micros.merge(groupName(d.name()), (System.nanoTime() - s) / 1000, Long::sum);
        }
        List<Finding> findings = resolve(raw);
        return new ScanResult(findings, micros, (System.nanoTime() - t0) / 1000);
    }

    private static String groupName(String detector) {
        return detector.startsWith("pattern:") ? "patterns" : detector;
    }

    /** Keeps the most important finding wherever spans overlap; document-level findings are kept per type and detail. */
    static List<Finding> resolve(List<Finding> raw) {
        List<Finding> spans = new ArrayList<>();
        Map<String, Finding> documentLevel = new LinkedHashMap<>();
        for (Finding f : raw) {
            if (f.type().spanBased()) spans.add(f);
            else documentLevel.merge(f.type() + "|" + f.detail(), f, (a, b) -> a.confidence() >= b.confidence() ? a : b);
        }
        spans.sort(PRIORITY);
        List<Finding> kept = new ArrayList<>();
        for (Finding f : spans) {
            boolean clash = false;
            for (Finding k : kept) {
                if (k.overlaps(f)) {
                    clash = true;
                    break;
                }
            }
            if (!clash) kept.add(f);
        }
        // If the heuristic and the semantic detector both caught an injection, report it once.
        List<Finding> docs = new ArrayList<>(documentLevel.values());
        List<Finding> injections = docs.stream().filter(f -> f.type() == EntityType.PROMPT_INJECTION).toList();
        if (injections.size() > 1) {
            Finding strongest = injections.stream().max(Comparator.comparingDouble(Finding::confidence)).orElseThrow();
            docs.removeIf(f -> f.type() == EntityType.PROMPT_INJECTION && f != strongest);
        }
        kept.addAll(docs);
        kept.sort(Comparator.comparingInt(Finding::start).thenComparing(f -> f.type().spanBased() ? 0 : 1));
        return kept;
    }
}
