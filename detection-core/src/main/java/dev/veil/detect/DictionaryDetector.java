package dev.veil.detect;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;

/**
 * Company-specific terms (project codenames, client names, internal hostnames) matched with an Aho–Corasick
 * automaton: one pass over the text finds every term, so a dictionary of 10,000 terms costs the same per
 * character as a dictionary of ten. Matching is case-insensitive and respects word boundaries.
 */
public final class DictionaryDetector implements Detector {

    /** A term and the label shown when it's found, e.g. ("Project Falcon", "Unannounced acquisition"). */
    public record Term(String text, String label) {
    }

    private static final class Node {
        final Map<Character, Node> next = new HashMap<>();
        Node fail;
        final List<Term> outputs = new ArrayList<>();
    }

    private final Node root = new Node();
    private final int size;

    public DictionaryDetector(Collection<Term> terms) {
        int n = 0;
        for (Term t : terms) {
            String key = t.text().trim().toLowerCase(Locale.ROOT);
            if (key.length() < 2) continue;
            Node cur = root;
            for (char ch : key.toCharArray()) cur = cur.next.computeIfAbsent(ch, c -> new Node());
            cur.outputs.add(new Term(key, t.label()));
            n++;
        }
        this.size = n;
        buildFailureLinks();
    }

    public int size() {
        return size;
    }

    @Override
    public String name() {
        return "dictionary";
    }

    @Override
    public List<Finding> detect(String text) {
        if (size == 0) return List.of();
        List<Finding> out = new ArrayList<>();
        String lower = text.toLowerCase(Locale.ROOT);
        Node cur = root;
        for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);
            while (cur != root && !cur.next.containsKey(ch)) cur = cur.fail;
            cur = cur.next.getOrDefault(ch, root);
            for (Node o = cur; o != root; o = o.fail) {
                for (Term t : o.outputs) {
                    int end = i + 1;
                    int start = end - t.text().length();
                    if (isBoundary(lower, start - 1) && isBoundary(lower, end)) {
                        out.add(new Finding(EntityType.CUSTOM_TERM, start, end, text.substring(start, end), 0.97,
                                name(), t.label() == null || t.label().isBlank() ? "Company term" : t.label()));
                    }
                }
            }
        }
        return out;
    }

    private static boolean isBoundary(String s, int i) {
        return i < 0 || i >= s.length() || !Character.isLetterOrDigit(s.charAt(i));
    }

    private void buildFailureLinks() {
        Queue<Node> queue = new ArrayDeque<>();
        root.fail = root;
        for (Node child : root.next.values()) {
            child.fail = root;
            queue.add(child);
        }
        while (!queue.isEmpty()) {
            Node node = queue.poll();
            for (Map.Entry<Character, Node> e : node.next.entrySet()) {
                char ch = e.getKey();
                Node child = e.getValue();
                Node f = node.fail;
                while (f != root && !f.next.containsKey(ch)) f = f.fail;
                Node target = f.next.get(ch);
                child.fail = target != null && target != child ? target : root;
                queue.add(child);
            }
        }
    }
}
