package dev.veil.detect;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fast, explainable prompt-injection signals (OWASP LLM01). The semantic detector catches paraphrases; these
 * catch the classics instantly and say exactly which phrase triggered.
 */
public final class InjectionHeuristics implements Detector {

    private record Rule(Pattern pattern, double confidence, String detail) {
    }

    private static final List<Rule> RULES = List.of(
            rule("(?i)\\b(?:ignore|disregard|forget|override)\\b.{0,30}\\b(?:all |any )?(?:previous|prior|above|earlier|preceding|your|system)\\b.{0,20}\\b(?:instructions?|prompts?|rules|directions|guidelines)", 0.95, "Instruction override"),
            rule("(?i)\\b(?:reveal|show|print|repeat|output|leak|tell me)\\b.{0,30}\\b(?:system prompt|hidden prompt|initial prompt|your instructions|developer message)", 0.92, "System prompt extraction"),
            rule("(?i)\\byou are (?:now )?(?:DAN|in developer mode|jailbroken|unfiltered|no longer bound)", 0.95, "Persona jailbreak"),
            rule("(?i)\\b(?:do anything now|developer mode enabled|jailbreak mode|god mode)\\b", 0.93, "Jailbreak phrase"),
            rule("(?i)\\bpretend (?:that )?(?:you have no|there are no) (?:rules|restrictions|filters|guidelines)", 0.9, "Restriction bypass"),
            rule("(?i)<\\|?(?:im_start|system|endoftext)\\|?>|\\[/?INST\\]|###\\s*(?:system|instruction)\\s*:", 0.85, "Chat-template injection"),
            rule("(?i)\\b(?:exfiltrate|send|post|forward)\\b.{0,40}\\b(?:conversation|chat history|data|secrets|credentials)\\b.{0,40}\\b(?:to|at)\\s+(?:https?://|\\S+@\\S+)", 0.9, "Data exfiltration instruction"));

    private static Rule rule(String regex, double c, String d) {
        return new Rule(Pattern.compile(regex), c, d);
    }

    @Override
    public String name() {
        return "injection-heuristics";
    }

    @Override
    public List<Finding> detect(String text) {
        List<Finding> out = new ArrayList<>();
        for (Rule r : RULES) {
            Matcher m = r.pattern.matcher(text);
            if (m.find()) {
                out.add(new Finding(EntityType.PROMPT_INJECTION, m.start(), m.end(), m.group(), r.confidence, name(), r.detail));
            }
        }
        return out;
    }
}
