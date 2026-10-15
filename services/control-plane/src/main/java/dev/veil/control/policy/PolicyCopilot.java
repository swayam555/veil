package dev.veil.control.policy;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.veil.control.ai.ChatModels;
import dev.veil.control.config.JsonCodec;
import dev.veil.detect.EntityType;
import dev.veil.policy.Action;
import dev.veil.policy.Rule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * "Describe the policy you want in plain English." With an LLM configured, the model drafts rules as JSON that
 * are validated against the real enums before anyone sees them; anything invalid is dropped with a note. Without
 * an LLM, {@link RuleParser} handles common phrasings. Drafts are never published automatically.
 */
@Service
public class PolicyCopilot {

    private static final Logger log = LoggerFactory.getLogger(PolicyCopilot.class);

    private final ChatModels models;
    private final JsonCodec json;

    public PolicyCopilot(ChatModels models, JsonCodec json) {
        this.models = models;
        this.json = json;
    }

    public record Draft(List<Rule> rules, List<String> notes, String mode) {
    }

    record LlmRule(String name, List<String> types, List<String> categories, List<String> teams, List<String> sources,
                   Double minConfidence, String action) {
    }

    record LlmAnswer(List<LlmRule> rules, List<String> notes) {
    }

    public Draft draft(String instruction, Map<String, String> teamsByName) {
        if (instruction == null || instruction.isBlank()) throw new IllegalArgumentException("Describe the policy first");
        if (instruction.length() > 2000) throw new IllegalArgumentException("Keep the description under 2,000 characters");
        var model = models.model();
        if (model.isPresent()) {
            try {
                String answer = model.get().chat(List.of(SystemMessage.from(systemPrompt(teamsByName)), UserMessage.from(instruction)))
                        .aiMessage().text();
                Draft d = fromLlm(answer, teamsByName);
                if (!d.rules().isEmpty()) return d;
            } catch (RuntimeException e) {
                log.warn("Copilot LLM failed, using the rule-based parser: {}", e.toString());
            }
        }
        RuleParser.Result r = RuleParser.parse(instruction, teamsByName);
        return new Draft(r.rules(), r.notes(), "rules");
    }

    Draft fromLlm(String answer, Map<String, String> teamsByName) {
        String body = answer.strip();
        int start = body.indexOf('{');
        int end = body.lastIndexOf('}');
        if (start < 0 || end < start) throw new IllegalArgumentException("No JSON in model answer");
        LlmAnswer parsed = json.read(body.substring(start, end + 1), LlmAnswer.class);
        List<Rule> rules = new ArrayList<>();
        List<String> notes = new ArrayList<>(parsed.notes() == null ? List.of() : parsed.notes());
        Set<String> teamIds = Set.copyOf(teamsByName.values());
        int n = 0;
        for (LlmRule r : parsed.rules() == null ? List.<LlmRule>of() : parsed.rules()) {
            try {
                Set<EntityType> types = EnumSet.noneOf(EntityType.class);
                for (String t : nn(r.types())) types.add(EntityType.valueOf(t));
                Set<EntityType.Category> cats = EnumSet.noneOf(EntityType.Category.class);
                for (String c : nn(r.categories())) cats.add(EntityType.Category.valueOf(c));
                Set<String> teams = new TreeSet<>();
                for (String t : nn(r.teams())) {
                    if (!teamIds.contains(t)) throw new IllegalArgumentException("unknown team " + t);
                    teams.add(t);
                }
                Set<String> sources = new TreeSet<>(nn(r.sources()));
                sources.removeIf(s -> !s.equals("api") && !s.equals("extension"));
                if (types.isEmpty() && cats.isEmpty()) throw new IllegalArgumentException("no data types");
                double min = r.minConfidence() == null ? 0.75 : Math.max(0.5, Math.min(0.99, r.minConfidence()));
                rules.add(new Rule("copilot-" + (++n), r.name() == null ? "Copilot rule " + n : r.name(), types, cats, teams,
                        sources, min, Action.valueOf(r.action()), true));
            } catch (RuntimeException e) {
                notes.add("Dropped a suggested rule (" + e.getMessage() + ")");
            }
        }
        return new Draft(rules, notes, "llm:" + models.description());
    }

    private static <T> List<T> nn(List<T> l) {
        return l == null ? List.of() : l;
    }

    private static String systemPrompt(Map<String, String> teamsByName) {
        String types = Arrays.stream(EntityType.values()).map(t -> t.name() + " (" + t.label() + ")").collect(Collectors.joining(", "));
        String cats = Arrays.stream(EntityType.Category.values()).map(Enum::name).collect(Collectors.joining(", "));
        String teams = teamsByName.entrySet().stream().map(e -> e.getValue() + " (" + e.getKey() + ")").collect(Collectors.joining(", "));
        return """
                You turn a security admin's plain-English data policy into rules for Veil, an AI data firewall.
                Reply with JSON only, no prose, in exactly this shape:
                {"rules":[{"name":"short title","types":["EMAIL"],"categories":["SECRET"],"teams":["support"],
                  "sources":["api"],"minConfidence":0.75,"action":"REDACT"}],"notes":["anything you could not express"]}
                - types: any of %s
                - categories: any of %s
                - teams: team ids from %s; empty list means every team
                - sources: "api" (apps using the proxy) and/or "extension" (employees in the browser); empty means both
                - action: ALLOW, WARN, REDACT, ROUTE_LOCAL (send to the organisation's on-premises model) or BLOCK
                - Use one rule per distinct instruction. Never invent teams. Put anything unclear in notes.
                """.formatted(types, cats, teams.isEmpty() ? "(none)" : teams);
    }
}
