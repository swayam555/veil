package dev.veil.insight.incidents;

import dev.veil.contracts.AuditEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentRulesTest {

    static AuditEvent event(String source, String action, List<AuditEvent.FindingSummary> findings, List<String> reasons) {
        return new AuditEvent("r", "o", "k", "support", "Support", null, source, "CHAT", "chatgpt.com", "gpt-4o-mini", null,
                action, "OK", reasons, findings, null, 0, 0, 0, 0, 0, 0, 0, false, false, 1, Instant.now());
    }

    static AuditEvent.FindingSummary f(String type, String severity, String action) {
        return new AuditEvent.FindingSummary(type, "X", severity, type, 0.9, 0, 1, null, action, "input");
    }

    @Test
    void whichEventsBecomeIncidents() {
        assertThat(IncidentService.severity(event("api", "BLOCK", List.of(f("API_KEY", "CRITICAL", "BLOCK")), List.of()))).contains("CRITICAL");
        assertThat(IncidentService.severity(event("api", "BLOCK", List.of(), List.of("Model not allowed")))).contains("HIGH");
        assertThat(IncidentService.severity(event("api", "WARN", List.of(f("PROMPT_INJECTION", "HIGH", "WARN")), List.of()))).contains("HIGH");
        assertThat(IncidentService.severity(event("api", "REDACT", List.of(f("EMAIL", "MEDIUM", "REDACT")), List.of()))).isEmpty();
        // A flagged paste is visible under Shadow AI but is not an incident.
        assertThat(IncidentService.severity(event("extension", "WARN", List.of(f("CONFIDENTIAL_TOPIC", "HIGH", "WARN")), List.of()))).isEmpty();
    }

    @Test
    void titlesNameTheDataOrTheReason() {
        assertThat(IncidentService.title(event("api", "BLOCK", List.of(), List.of()), new java.util.TreeSet<>(Set.of("API_KEY", "PASSWORD"))))
                .isEqualTo("API key or token and Password blocked for gpt-4o-mini (Support)");
        assertThat(IncidentService.title(event("extension", "BLOCK", List.of(), List.of()), Set.of("CONFIDENTIAL_TOPIC")))
                .isEqualTo("Confidential topic blocked on chatgpt.com (Support)");
        assertThat(IncidentService.title(event("api", "BLOCK", List.of(), List.of("Model 'o1-pro' is not on the organisation's allowlist")), Set.of()))
                .isEqualTo("Model 'o1-pro' is not on the organisation's allowlist (Support)");
    }
}
