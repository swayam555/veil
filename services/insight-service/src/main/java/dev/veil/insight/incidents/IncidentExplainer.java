package dev.veil.insight.incidents;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.veil.detect.EntityType;
import dev.veil.insight.ai.ChatModels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Explains an incident in plain language for the person triaging it: what happened, why it matters (which
 * regulations), and what to do next. The model only ever sees data types, the decision and the REDACTED prompt,
 * never a sensitive value. Without an LLM a structured template produces the same sections.
 */
@Service
public class IncidentExplainer {

    private static final Logger log = LoggerFactory.getLogger(IncidentExplainer.class);

    private final ChatModels models;

    public IncidentExplainer(ChatModels models) {
        this.models = models;
    }

    public record Facts(String title, String severity, String action, List<String> types, String teamName, String actor,
                        String source, String site, String model, int occurrences, List<String> reasons, String redactedPrompt) {
    }

    public record Explanation(String text, String by) {
    }

    public Explanation explain(Facts f) {
        var model = models.model();
        if (model.isPresent()) {
            try {
                String text = model.get().chat(List.of(SystemMessage.from("""
                                You are a data-protection analyst explaining a security incident from Veil, an AI data firewall,
                                to the person who must triage it. Be concise and concrete. Use exactly three short sections with
                                these headings: "What happened", "Why it matters", "What to do". Plain text, no markdown tables.
                                Never guess at the redacted values in placeholders like [EMAIL_1]."""),
                        UserMessage.from(facts(f)))).aiMessage().text();
                if (text != null && !text.isBlank()) return new Explanation(text.strip(), "llm:" + models.description());
            } catch (RuntimeException e) {
                log.warn("Explainer LLM failed, using the template: {}", e.toString());
            }
        }
        return new Explanation(template(f), "template");
    }

    private static String facts(Facts f) {
        return """
                Incident: %s
                Severity: %s; decision: %s; occurrences: %d
                Data types: %s
                Team: %s; person: %s; channel: %s%s%s
                Policy reasons: %s
                Redacted prompt excerpt: %s
                """.formatted(f.title(), f.severity(), f.action(), f.occurrences(), String.join(", ", labels(f.types())),
                nz(f.teamName()), nz(f.actor()), f.source(), f.site() == null ? "" : " (" + f.site() + ")",
                f.model() == null ? "" : "; model " + f.model(), String.join("; ", f.reasons()),
                f.redactedPrompt() == null ? "(not stored)" : f.redactedPrompt().length() > 600 ? f.redactedPrompt().substring(0, 600) + "…" : f.redactedPrompt());
    }

    static String template(Facts f) {
        StringBuilder b = new StringBuilder();
        String channel = "extension".equals(f.source()) ? "while using " + (f.site() == null ? "an AI website" : f.site())
                : "through the API" + (f.model() == null ? "" : " to " + f.model());
        b.append("What happened\n");
        b.append(nz(f.actor()).equals("-") ? "A request" : f.actor()).append(" from ").append(nz(f.teamName()))
                .append(" sent ").append(String.join(", ", labels(f.types())).toLowerCase()).append(' ').append(channel).append(". ");
        b.append(switch (f.action()) {
            case "BLOCK" -> "Veil blocked it, so nothing reached the model.";
            case "REDACT" -> "Veil replaced the values with placeholders before sending, and restored them in the answer.";
            case "ROUTE_LOCAL" -> "Veil sent it to the on-premises model instead, so the data never left the company.";
            case "WARN" -> "The policy allows this but flags it, so the content was sent and the user was warned.";
            default -> "It was allowed.";
        });
        if (f.occurrences() > 1) b.append(" This has happened ").append(f.occurrences()).append(" times in a short window.");
        b.append("\n\nWhy it matters\n");
        Set<String> regs = new LinkedHashSet<>();
        for (String t : f.types()) {
            try {
                regs.addAll(EntityType.valueOf(t).regulations());
            } catch (IllegalArgumentException ignored) {
                // Unknown type from a newer proxy; skip.
            }
        }
        if (f.types().stream().anyMatch(t -> t.equals("API_KEY") || t.equals("PASSWORD") || t.equals("PRIVATE_KEY") || t.equals("JWT"))) {
            b.append("Credentials pasted into an AI tool can end up in provider logs or training data; anyone who obtains them can act as the owner. ");
        }
        if (f.types().contains("PROMPT_INJECTION")) {
            b.append("Prompt injection tries to make the model ignore its instructions, which can leak data or trigger unintended actions (OWASP LLM01). ");
        }
        if (f.types().contains("CONFIDENTIAL_TOPIC")) {
            b.append("Unreleased or privileged business information sent to an external model may breach confidentiality duties. ");
        }
        if (f.types().stream().anyMatch(t -> List.of("AADHAAR", "PAN", "US_SSN", "EMAIL", "PHONE", "PERSON", "CREDIT_CARD", "IBAN", "BANK_ACCOUNT").contains(t))) {
            b.append("Personal and financial data sent to a provider outside the company is a cross-border transfer under privacy law. ");
        }
        if (!regs.isEmpty()) b.append("Relevant: ").append(String.join(", ", regs)).append('.');
        b.append("\n\nWhat to do\n");
        if (f.types().stream().anyMatch(t -> t.equals("API_KEY") || t.equals("PASSWORD") || t.equals("PRIVATE_KEY"))) {
            b.append("1. Rotate the exposed credential now, even though Veil blocked this request: it may have been shared elsewhere.\n");
        } else {
            b.append("1. Confirm with ").append(nz(f.actor()).equals("-") ? "the key owner" : f.actor()).append(" what they were trying to do.\n");
        }
        b.append("2. If this is a legitimate need, give the team an approved route (for example, the on-premises model).\n");
        b.append("3. Mark this incident resolved, or as a false positive so the policy can be tuned.");
        return b.toString();
    }

    private static List<String> labels(List<String> types) {
        return types.stream().map(t -> {
            try {
                return EntityType.valueOf(t).label();
            } catch (IllegalArgumentException e) {
                return t;
            }
        }).toList();
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }
}
