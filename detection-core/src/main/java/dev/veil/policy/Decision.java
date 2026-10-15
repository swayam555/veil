package dev.veil.policy;

import dev.veil.detect.Finding;

import java.util.List;

/**
 * The policy engine's verdict for one request.
 *
 * @param action   overall action (the strictest across findings and request checks)
 * @param findings each finding with the action that applies to it and the rule that decided it
 * @param reasons  human-readable explanation, shown in the console and returned with blocked requests
 */
public record Decision(Action action, List<FindingDecision> findings, List<String> reasons) {

    public record FindingDecision(Finding finding, Action action, String ruleId) {
    }

    /** The span findings to replace with placeholders before the text leaves. */
    public List<Finding> toRedact() {
        return findings.stream()
                .filter(fd -> fd.action() == Action.REDACT && fd.finding().type().spanBased())
                .map(FindingDecision::finding)
                .toList();
    }

    public boolean blocked() {
        return action == Action.BLOCK;
    }
}
