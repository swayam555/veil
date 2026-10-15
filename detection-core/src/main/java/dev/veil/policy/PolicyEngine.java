package dev.veil.policy;

import dev.veil.detect.Finding;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Pure function from (policy, findings, request context) to a {@link Decision}. No I/O, trivially testable. */
public final class PolicyEngine {

    /**
     * @param team   team of the virtual key or the extension user, may be null
     * @param source {@code api} or {@code extension}
     * @param model  model requested, null for the Scan API and the extension
     */
    public record Context(String team, String source, String model) {
    }

    private PolicyEngine() {
    }

    public static Decision decide(Policy policy, List<Finding> findings, Context ctx) {
        Action overall = Action.ALLOW;
        List<Decision.FindingDecision> decisions = new ArrayList<>(findings.size());
        Set<String> reasons = new LinkedHashSet<>();

        if (ctx.model() != null && !policy.allowedModels().isEmpty() && !policy.allowedModels().contains(ctx.model())) {
            overall = Action.BLOCK;
            reasons.add("Model '" + ctx.model() + "' is not on the organisation's allowlist");
        }

        for (Finding f : findings) {
            Action action = Action.ALLOW;
            Rule decidedBy = null;
            for (Rule r : policy.rules()) {
                if (r.appliesTo(f, ctx.team(), ctx.source()) && (decidedBy == null || r.action().ordinal() > action.ordinal())) {
                    action = r.action();
                    decidedBy = r;
                }
            }
            if (action == Action.REDACT && !f.type().spanBased()) {
                // A topic or a block of code can't be replaced by a placeholder; flag it instead.
                action = Action.WARN;
            }
            decisions.add(new Decision.FindingDecision(f, action, decidedBy == null ? null : decidedBy.id()));
            overall = overall.max(action);
            if (decidedBy != null && action != Action.ALLOW) {
                reasons.add(verb(action) + " " + f.type().label().toLowerCase()
                        + (f.detail() != null && !f.detail().equals(f.type().label()) ? " (" + f.detail() + ")" : "")
                        + " — rule '" + decidedBy.name() + "'");
            }
        }
        return new Decision(overall, List.copyOf(decisions), List.copyOf(reasons));
    }

    private static String verb(Action a) {
        return switch (a) {
            case BLOCK -> "Blocked:";
            case ROUTE_LOCAL -> "Kept on premises:";
            case REDACT -> "Redacted:";
            case WARN -> "Flagged:";
            case ALLOW -> "Allowed:";
        };
    }
}
