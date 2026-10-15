package dev.veil.policy;

import dev.veil.detect.EntityType;
import dev.veil.detect.Finding;

import java.util.Set;

/**
 * "When any of these types (or categories) is found with at least this confidence, for these teams and
 * sources, do this." Empty sets mean "any".
 *
 * @param sources {@code api} (proxy and Scan API) or {@code extension} (browser); empty = both
 */
public record Rule(
        String id,
        String name,
        Set<EntityType> types,
        Set<EntityType.Category> categories,
        Set<String> teams,
        Set<String> sources,
        double minConfidence,
        Action action,
        boolean enabled) {

    public Rule {
        types = types == null ? Set.of() : Set.copyOf(types);
        categories = categories == null ? Set.of() : Set.copyOf(categories);
        teams = teams == null ? Set.of() : Set.copyOf(teams);
        sources = sources == null ? Set.of() : Set.copyOf(sources);
        if (action == null) throw new IllegalArgumentException("Rule " + id + " has no action");
        if (minConfidence < 0 || minConfidence > 1) throw new IllegalArgumentException("minConfidence must be 0..1");
    }

    public static Rule of(String id, String name, Set<EntityType> types, Action action, double minConfidence) {
        return new Rule(id, name, types, Set.of(), Set.of(), Set.of(), minConfidence, action, true);
    }

    public boolean appliesTo(Finding f, String team, String source) {
        if (!enabled) return false;
        if (f.confidence() < minConfidence) return false;
        if (!teams.isEmpty() && (team == null || !teams.contains(team))) return false;
        if (!sources.isEmpty() && (source == null || !sources.contains(source))) return false;
        if (types.isEmpty() && categories.isEmpty()) return true;
        return types.contains(f.type()) || categories.contains(f.type().category());
    }
}
