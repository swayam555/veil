package dev.veil.proxy.llm;

import dev.veil.contracts.OrgConfig;
import dev.veil.detect.RedactionVault;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;

/**
 * A free, offline stand-in for a real LLM, so Veil runs end to end with no API key. It writes a plausible,
 * template-based answer to the last user message and deliberately reuses any placeholders it was given, which
 * shows the round trip: the model only ever saw {@code [PERSON_1]}; the user gets the real name back.
 */
@Component
public class EchoProvider implements LlmProvider {

    private final ProviderFaults faults;

    public EchoProvider(ProviderFaults faults) {
        this.faults = faults;
    }

    @Override
    public OrgConfig.ProviderKind kind() {
        return OrgConfig.ProviderKind.ECHO;
    }

    @Override
    public Mono<Completion> complete(ProviderCall call) {
        String text = answer(call.body());
        int in = Payloads.promptTokens(call.body());
        int out = Payloads.estimateTokens(text);
        ObjectNode response = Payloads.completion(Payloads.newId(), call.upstreamModel(), text, "stop", in, out);
        long latency = 250 + ThreadLocalRandom.current().nextInt(350) + faults.get(call.provider().id()).extraLatencyMs();
        return maybeFail(call).then(Mono.delay(Duration.ofMillis(latency))).thenReturn(new Completion(response, in, out));
    }

    @Override
    public Flux<ObjectNode> stream(ProviderCall call) {
        String text = answer(call.body());
        int in = Payloads.promptTokens(call.body());
        String id = Payloads.newId();
        List<String> pieces = split(text);
        long firstToken = 150 + ThreadLocalRandom.current().nextInt(150) + faults.get(call.provider().id()).extraLatencyMs();
        Flux<ObjectNode> body = Flux.range(0, pieces.size())
                .concatMap(i -> Mono.delay(Duration.ofMillis(i == 0 ? firstToken : 18))
                        .thenReturn(Payloads.chunk(id, call.upstreamModel(), pieces.get(i), null, i == 0)));
        Flux<ObjectNode> tail = Flux.just(Payloads.chunk(id, call.upstreamModel(), null, "stop", false),
                Payloads.usageChunk(id, call.upstreamModel(), in, Payloads.estimateTokens(text)));
        return maybeFail(call).thenMany(body.concatWith(tail));
    }

    private Mono<Void> maybeFail(ProviderCall call) {
        double rate = faults.get(call.provider().id()).errorRate();
        if (rate > 0 && ThreadLocalRandom.current().nextDouble() < rate) {
            return Mono.delay(Duration.ofMillis(40)).then(Mono.error(
                    new UpstreamException("Simulated outage of " + call.provider().name() + " (fault injection)", 503, true)));
        }
        return Mono.empty();
    }

    /** Splits into small word groups so streaming looks like a real model, and placeholders often straddle chunks. */
    static List<String> split(String text) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int len = 3 + (i * 7 % 9);
            int end = Math.min(text.length(), i + len);
            out.add(text.substring(i, end));
            i = end;
        }
        return out;
    }

    static String answer(JsonNode body) {
        String user = "";
        for (JsonNode m : body.path("messages").values()) {
            if ("user".equals(m.path("role").asString(""))) user = Payloads.contentText(m);
        }
        Set<String> placeholders = new LinkedHashSet<>();
        Matcher pm = RedactionVault.PLACEHOLDER.matcher(user);
        while (pm.find()) placeholders.add(pm.group());
        String person = placeholders.stream().filter(p -> p.startsWith("[PERSON_")).findFirst().orElse(null);
        String lower = user.toLowerCase(Locale.ROOT);
        String topic = topic(user);
        StringBuilder a = new StringBuilder();

        if (lower.contains("translate")) {
            a.append("Here is the translation:\n\n");
            a.append("“").append(user.replaceFirst("(?i)^.*?translate[^:]*:\\s*", "")).append("”\n\n");
            a.append("(Veil Echo is a simulated model, so the text is returned as is. Connect a real provider to translate.)");
        } else if (lower.matches("(?s).*\\b(reply|email|letter|draft|write|respond|message)\\b.*")) {
            a.append(person != null ? "Dear " + person + ",\n\n" : "Hello,\n\n");
            a.append("Thank you for reaching out, and I'm sorry for the trouble. ");
            a.append("I've looked into ").append(topic).append(" and here is where things stand:\n\n");
            a.append("1. We have confirmed the details you shared and logged the request.\n");
            a.append("2. The team will resolve it within 5 working days and keep you posted.\n");
            a.append("3. If anything changes, we'll contact you right away.\n\n");
            List<String> contacts = placeholders.stream().filter(p -> !p.equals(person)).limit(3).toList();
            if (!contacts.isEmpty()) a.append("For reference, we'll use ").append(String.join(", ", contacts)).append(" for any follow-up.\n\n");
            a.append("Warm regards,\nAcme Support");
        } else if (lower.contains("summar")) {
            a.append("Summary:\n\n");
            a.append("- The request concerns ").append(topic).append(".\n");
            if (!placeholders.isEmpty()) a.append("- People and identifiers involved: ").append(String.join(", ", placeholders)).append(".\n");
            a.append("- Suggested next step: confirm the facts, then reply with a clear timeline.");
        } else if (user.contains("{") || user.contains(";") || lower.contains("code") || lower.contains("error")) {
            a.append("A few suggestions:\n\n");
            a.append("1. Check the inputs before using them, and fail with a clear message.\n");
            a.append("2. Avoid loading everything into memory; filter in the database and paginate.\n");
            a.append("3. Add a unit test for the edge case you're seeing.\n\n");
            a.append("If this involves credentials, rotate them and load them from a secret manager instead of pasting them.");
        } else {
            a.append("Here's a concise answer about ").append(topic).append(":\n\n");
            a.append("1. Start with the goal and the constraints.\n");
            a.append("2. Compare the main options and their trade-offs.\n");
            a.append("3. Pick the simplest option that meets the constraints, and revisit it as you learn more.");
            if (!placeholders.isEmpty()) a.append("\n\nI've kept the details you mentioned (").append(String.join(", ", placeholders)).append(") as given.");
        }
        return a.toString();
    }

    private static String topic(String user) {
        String s = user.replaceAll("\\s+", " ").trim();
        int cut = s.indexOf('.');
        if (cut > 20) s = s.substring(0, cut);
        if (s.length() > 90) s = s.substring(0, 90).replaceAll("\\s+\\S*$", "") + "…";
        return s.isEmpty() ? "your request" : "“" + s + "”";
    }
}
