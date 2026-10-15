package dev.veil.policy;

import dev.veil.detect.EntityType;

import java.util.List;
import java.util.Set;

/**
 * An organisation's policy. Rules are evaluated for every finding; the strictest matching action wins.
 *
 * @param allowedModels       model names or aliases users may request; empty = any
 * @param maxOutputTokens     cap on {@code max_tokens}; 0 = no cap
 * @param failMode            what to do if detection itself fails or times out
 * @param semanticCache       serve close repeats of a (redacted) prompt from cache
 * @param storeRedactedPrompts keep the redacted prompt in the audit log for review (it contains no detected values)
 * @param outputScanning      scan model answers for secrets and personal data the model produced itself
 */
public record Policy(
        int version,
        List<Rule> rules,
        Set<String> allowedModels,
        int maxOutputTokens,
        FailMode failMode,
        boolean semanticCache,
        boolean storeRedactedPrompts,
        boolean outputScanning) {

    public enum FailMode { OPEN, CLOSED }

    public Policy {
        rules = rules == null ? List.of() : List.copyOf(rules);
        allowedModels = allowedModels == null ? Set.of() : Set.copyOf(allowedModels);
        failMode = failMode == null ? FailMode.CLOSED : failMode;
    }

    public static Policy allowAll() {
        return new Policy(0, List.of(), Set.of(), 0, FailMode.OPEN, false, true, false);
    }

    /** The policy every new organisation starts with: block secrets and injections, redact personal data. */
    public static Policy recommended() {
        return new Policy(1, List.of(
                new Rule("block-secrets", "Block credentials and keys",
                        Set.of(), Set.of(EntityType.Category.SECRET), Set.of(), Set.of(), 0.7, Action.BLOCK, true),
                new Rule("block-injection", "Block prompt injection",
                        Set.of(EntityType.PROMPT_INJECTION), Set.of(), Set.of(), Set.of("api"), 0.8, Action.BLOCK, true),
                new Rule("warn-injection-browser", "Warn on pasted prompt injection",
                        Set.of(EntityType.PROMPT_INJECTION), Set.of(), Set.of(), Set.of("extension"), 0.8, Action.WARN, true),
                new Rule("redact-ids", "Redact government IDs",
                        Set.of(), Set.of(EntityType.Category.GOVERNMENT_ID), Set.of(), Set.of(), 0.7, Action.REDACT, true),
                new Rule("redact-financial", "Redact card and bank details",
                        Set.of(EntityType.CREDIT_CARD, EntityType.IBAN, EntityType.BANK_ACCOUNT), Set.of(), Set.of(), Set.of(), 0.7, Action.REDACT, true),
                new Rule("redact-contact", "Redact contact details and names",
                        Set.of(EntityType.EMAIL, EntityType.PHONE, EntityType.PERSON), Set.of(), Set.of(), Set.of(), 0.7, Action.REDACT, true),
                new Rule("redact-company-terms", "Redact company terms",
                        Set.of(EntityType.CUSTOM_TERM), Set.of(), Set.of(), Set.of(), 0.7, Action.REDACT, true),
                new Rule("warn-confidential", "Flag confidential topics",
                        Set.of(EntityType.CONFIDENTIAL_TOPIC), Set.of(), Set.of(), Set.of(), 0.65, Action.WARN, true),
                new Rule("warn-source-code", "Flag source code",
                        Set.of(EntityType.SOURCE_CODE), Set.of(), Set.of(), Set.of(), 0.7, Action.WARN, true)),
                Set.of(), 4096, FailMode.CLOSED, true, true, true);
    }
}
