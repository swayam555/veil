package dev.veil.control.policy;

import dev.veil.detect.EntityType;
import dev.veil.policy.Action;
import dev.veil.policy.Rule;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The copilot's offline mode: turns plain-English instructions into rules without an LLM. It understands
 * sentences like "Block API keys and passwords everywhere; redact Aadhaar and PAN for the support team;
 * keep confidential topics on-prem for finance". Each clause becomes one rule.
 */
public final class RuleParser {

    private static final Map<String, Action> ACTIONS = new LinkedHashMap<>();
    private static final Map<String, Set<EntityType>> TYPES = new LinkedHashMap<>();
    private static final Map<String, Set<EntityType.Category>> CATEGORIES = new LinkedHashMap<>();

    static {
        ACTIONS.put("(?:keep|route|send)\\b.{0,40}\\b(?:on[- ]?prem(?:ise|ises)?|local|in[- ]house|internal model)", Action.ROUTE_LOCAL);
        ACTIONS.put("\\b(?:block|stop|prevent|deny|reject|refuse|forbid|never allow|ban)\\b", Action.BLOCK);
        ACTIONS.put("\\b(?:redact|mask|hide|remove|strip|anonymi[sz]e|scrub|replace)\\b", Action.REDACT);
        ACTIONS.put("\\b(?:warn|flag|alert|log|notify|monitor|track)\\b", Action.WARN);
        ACTIONS.put("\\b(?:allow|permit|let)\\b", Action.ALLOW);

        TYPES.put("aadhaa?r|aadhar|uid", Set.of(EntityType.AADHAAR));
        TYPES.put("\\bpan\\b|pan cards?|pan numbers?", Set.of(EntityType.PAN));
        TYPES.put("gstin|gst numbers?", Set.of(EntityType.GSTIN));
        TYPES.put("ssns?|social security", Set.of(EntityType.US_SSN));
        TYPES.put("(?:credit|debit|payment)? ?cards?(?: numbers?)?", Set.of(EntityType.CREDIT_CARD));
        TYPES.put("iban", Set.of(EntityType.IBAN));
        TYPES.put("bank accounts?|account numbers?", Set.of(EntityType.BANK_ACCOUNT));
        TYPES.put("ifsc", Set.of(EntityType.IFSC));
        TYPES.put("e-?mails?", Set.of(EntityType.EMAIL));
        TYPES.put("phones?|mobiles?|phone numbers?", Set.of(EntityType.PHONE));
        TYPES.put("\\bnames?\\b|people'?s names", Set.of(EntityType.PERSON));
        TYPES.put("ip addresses|\\bips?\\b", Set.of(EntityType.IP_ADDRESS));
        TYPES.put("api keys?|tokens?|access keys?", Set.of(EntityType.API_KEY, EntityType.JWT));
        TYPES.put("passwords?|passcodes?", Set.of(EntityType.PASSWORD));
        TYPES.put("private keys?|ssh keys?", Set.of(EntityType.PRIVATE_KEY));
        TYPES.put("source code|\\bcode\\b", Set.of(EntityType.SOURCE_CODE));
        TYPES.put("prompt injections?|jailbreaks?|injections?", Set.of(EntityType.PROMPT_INJECTION));
        TYPES.put("confidential|financial results|earnings|m&a|mergers?|acquisitions?|salar(?:y|ies)|health|medical|patient|legal|layoffs?", Set.of(EntityType.CONFIDENTIAL_TOPIC));
        TYPES.put("codenames?|company terms|project names|client names|dictionary", Set.of(EntityType.CUSTOM_TERM));

        CATEGORIES.put("secrets?|credentials?", Set.of(EntityType.Category.SECRET));
        CATEGORIES.put("pii|personal (?:data|information|details)|personally identifiable", Set.of(EntityType.Category.CONTACT,
                EntityType.Category.PERSONAL, EntityType.Category.GOVERNMENT_ID));
        CATEGORIES.put("government ids?|national ids?|identity numbers?", Set.of(EntityType.Category.GOVERNMENT_ID));
        CATEGORIES.put("financial (?:data|details|information)|banking details", Set.of(EntityType.Category.FINANCIAL));
        CATEGORIES.put("contact (?:details|information)", Set.of(EntityType.Category.CONTACT));
    }

    public record Result(List<Rule> rules, List<String> notes) {
    }

    private RuleParser() {
    }

    public static Result parse(String instruction, Map<String, String> teamsByName) {
        List<Rule> rules = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        String[] clauses = instruction.split("(?<=[.;\\n])|\\bthen\\b|\\balso\\b|,\\s*(?:and\\s+)?(?=(?:block|redact|mask|warn|flag|keep|route|allow|stop|hide)\\b)");
        int n = 0;
        for (String raw : clauses) {
            String clause = raw.trim().toLowerCase(Locale.ROOT);
            if (clause.replaceAll("[^a-z]", "").length() < 3) continue;
            Action action = null;
            for (var e : ACTIONS.entrySet()) {
                if (Pattern.compile(e.getKey()).matcher(clause).find()) {
                    action = e.getValue();
                    break;
                }
            }
            Set<EntityType> types = EnumSet.noneOf(EntityType.class);
            Set<EntityType.Category> categories = EnumSet.noneOf(EntityType.Category.class);
            String withoutActionWords = clause;
            for (var e : TYPES.entrySet()) {
                if (Pattern.compile("(?<![a-z])(?:" + e.getKey() + ")(?![a-z])").matcher(withoutActionWords).find()) types.addAll(e.getValue());
            }
            for (var e : CATEGORIES.entrySet()) {
                if (Pattern.compile("(?<![a-z])(?:" + e.getKey() + ")(?![a-z])").matcher(withoutActionWords).find()) categories.addAll(e.getValue());
            }
            if (action == null) {
                notes.add("Skipped \"" + raw.trim() + "\": no action (block, redact, warn, keep on-prem or allow).");
                continue;
            }
            if (types.isEmpty() && categories.isEmpty()) {
                notes.add("Skipped \"" + raw.trim() + "\": no data type I recognise.");
                continue;
            }
            Set<String> teams = new TreeSet<>();
            teamsByName.forEach((name, id) -> {
                if (Pattern.compile("\\b" + Pattern.quote(name.toLowerCase(Locale.ROOT)) + "\\b").matcher(clause).find()) teams.add(id);
            });
            Set<String> sources = new TreeSet<>();
            if (clause.matches(".*\\b(?:browser|extension|chatgpt|chat websites?|employees? (?:pasting|paste))\\b.*")) sources.add("extension");
            if (clause.matches(".*\\b(?:apps|applications|services|bots|via the api|through the api|api traffic|api calls|the proxy)\\b.*")) sources.add("api");
            double minConfidence = types.contains(EntityType.CONFIDENTIAL_TOPIC) || types.contains(EntityType.PROMPT_INJECTION) ? 0.7 : 0.75;
            n++;
            String name = capitalise(raw.trim().replaceAll("[.;]+$", ""));
            rules.add(new Rule("copilot-" + n, name.length() > 80 ? name.substring(0, 77) + "…" : name, types, categories,
                    teams, sources, minConfidence, action, true));
        }
        if (rules.isEmpty() && notes.isEmpty()) notes.add("I couldn't find any instruction in that text.");
        return new Result(rules, notes);
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
