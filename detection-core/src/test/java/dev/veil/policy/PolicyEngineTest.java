package dev.veil.policy;

import dev.veil.detect.EntityType;
import dev.veil.detect.Finding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyEngineTest {

    private static Finding f(EntityType t, double c) {
        return new Finding(t, 0, 5, "value", c, "test", t.label());
    }

    private static final PolicyEngine.Context API = new PolicyEngine.Context("support", "api", "gpt-4o-mini");

    @Test
    void strictestActionWins() {
        Decision d = PolicyEngine.decide(Policy.recommended(),
                List.of(f(EntityType.EMAIL, 0.99), f(EntityType.API_KEY, 0.99)), API);
        assertThat(d.action()).isEqualTo(Action.BLOCK);
        assertThat(d.findings()).extracting(Decision.FindingDecision::action).containsExactly(Action.REDACT, Action.BLOCK);
        assertThat(d.reasons()).anyMatch(r -> r.startsWith("Blocked: api key"));
    }

    @Test
    void redactListContainsOnlySpanFindingsMarkedRedact() {
        Decision d = PolicyEngine.decide(Policy.recommended(),
                List.of(f(EntityType.EMAIL, 0.99), f(EntityType.CONFIDENTIAL_TOPIC, 0.9), f(EntityType.IP_ADDRESS, 0.9)), API);
        assertThat(d.action()).isEqualTo(Action.REDACT);
        assertThat(d.toRedact()).extracting(Finding::type).containsExactly(EntityType.EMAIL);
    }

    @Test
    void confidenceThresholdsAndEmptyPolicy() {
        assertThat(PolicyEngine.decide(Policy.recommended(), List.of(f(EntityType.EMAIL, 0.6)), API).action()).isEqualTo(Action.ALLOW);
        assertThat(PolicyEngine.decide(Policy.allowAll(), List.of(f(EntityType.API_KEY, 0.99)), API).action()).isEqualTo(Action.ALLOW);
    }

    @Test
    void teamAndSourceScopedRules() {
        Rule financeLocal = new Rule("fin", "Finance stays local", Set.of(EntityType.CONFIDENTIAL_TOPIC), Set.of(),
                Set.of("finance"), Set.of(), 0.6, Action.ROUTE_LOCAL, true);
        Policy p = new Policy(2, List.of(financeLocal), Set.of(), 0, Policy.FailMode.CLOSED, false, true, false);
        var topic = List.of(f(EntityType.CONFIDENTIAL_TOPIC, 0.8));
        assertThat(PolicyEngine.decide(p, topic, new PolicyEngine.Context("finance", "api", "m")).action()).isEqualTo(Action.ROUTE_LOCAL);
        assertThat(PolicyEngine.decide(p, topic, new PolicyEngine.Context("support", "api", "m")).action()).isEqualTo(Action.ALLOW);

        var injection = List.of(f(EntityType.PROMPT_INJECTION, 0.9));
        assertThat(PolicyEngine.decide(Policy.recommended(), injection, new PolicyEngine.Context(null, "api", null)).action()).isEqualTo(Action.BLOCK);
        assertThat(PolicyEngine.decide(Policy.recommended(), injection, new PolicyEngine.Context(null, "extension", null)).action()).isEqualTo(Action.WARN);
    }

    @Test
    void documentLevelRedactBecomesWarn() {
        Rule redactCode = Rule.of("code", "Redact code", Set.of(EntityType.SOURCE_CODE), Action.REDACT, 0.5);
        Policy p = new Policy(1, List.of(redactCode), Set.of(), 0, Policy.FailMode.OPEN, false, true, false);
        Decision d = PolicyEngine.decide(p, List.of(f(EntityType.SOURCE_CODE, 0.9)), API);
        assertThat(d.action()).isEqualTo(Action.WARN);
    }

    @Test
    void modelAllowlist() {
        Policy p = new Policy(1, List.of(), Set.of("veil-echo", "gpt-4o-mini"), 0, Policy.FailMode.OPEN, false, true, false);
        assertThat(PolicyEngine.decide(p, List.of(), new PolicyEngine.Context("t", "api", "gpt-4o-mini")).action()).isEqualTo(Action.ALLOW);
        Decision d = PolicyEngine.decide(p, List.of(), new PolicyEngine.Context("t", "api", "o1-pro"));
        assertThat(d.action()).isEqualTo(Action.BLOCK);
        assertThat(d.reasons()).containsExactly("Model 'o1-pro' is not on the organisation's allowlist");
        // The Scan API has no model, so the allowlist doesn't apply.
        assertThat(PolicyEngine.decide(p, List.of(), new PolicyEngine.Context("t", "api", null)).action()).isEqualTo(Action.ALLOW);
    }

    @Test
    void disabledRulesAreIgnoredAndCategoriesMatch() {
        Rule disabled = new Rule("x", "off", Set.of(EntityType.EMAIL), Set.of(), Set.of(), Set.of(), 0.1, Action.BLOCK, false);
        Rule financial = new Rule("y", "fin", Set.of(), Set.of(EntityType.Category.FINANCIAL), Set.of(), Set.of(), 0.1, Action.REDACT, true);
        Policy p = new Policy(1, List.of(disabled, financial), Set.of(), 0, Policy.FailMode.OPEN, false, true, false);
        assertThat(PolicyEngine.decide(p, List.of(f(EntityType.EMAIL, 1)), API).action()).isEqualTo(Action.ALLOW);
        assertThat(PolicyEngine.decide(p, List.of(f(EntityType.IBAN, 1)), API).action()).isEqualTo(Action.REDACT);
    }
}
