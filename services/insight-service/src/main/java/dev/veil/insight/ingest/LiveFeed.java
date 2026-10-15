package dev.veil.insight.ingest;

import dev.veil.contracts.AuditEvent;
import dev.veil.contracts.Topics;
import dev.veil.insight.config.JsonCodec;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Pushes every new request to the browsers watching the console, per organisation, over Server-Sent Events. */
@Component
public class LiveFeed {

    private static final int MAX_SUBSCRIBERS_PER_ORG = 200;

    private final Map<String, List<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final JsonCodec json;

    public LiveFeed(JsonCodec json) {
        this.json = json;
    }

    public SseEmitter subscribe(String orgId) {
        List<SseEmitter> list = subscribers.computeIfAbsent(orgId, k -> new CopyOnWriteArrayList<>());
        if (list.size() >= MAX_SUBSCRIBERS_PER_ORG) throw new IllegalArgumentException("Too many live connections");
        SseEmitter emitter = new SseEmitter(0L);
        list.add(emitter);
        Runnable remove = () -> list.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(t -> remove.run());
        try {
            emitter.send(SseEmitter.event().name("hello").data("{}"));
        } catch (IOException e) {
            remove.run();
        }
        return emitter;
    }

    @KafkaListener(topics = Topics.AUDIT, containerFactory = "liveFactory")
    public void onEvent(String payload) {
        AuditEvent e = json.read(payload, AuditEvent.class);
        List<SseEmitter> list = subscribers.get(e.orgId());
        if (list == null || list.isEmpty()) return;
        String data = json.write(Map.ofEntries(
                Map.entry("id", e.id()), Map.entry("at", e.at().toString()), Map.entry("team", String.valueOf(e.teamName())),
                Map.entry("source", e.source()), Map.entry("kind", e.kind()), Map.entry("site", String.valueOf(e.site())),
                Map.entry("model", String.valueOf(e.model())), Map.entry("action", e.action()), Map.entry("status", e.status()),
                Map.entry("types", e.findings().stream().map(AuditEvent.FindingSummary::type).distinct().toList()),
                Map.entry("latencyMs", e.latencyMs()), Map.entry("cacheHit", e.cacheHit()), Map.entry("costMicros", e.costMicros())));
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("request").data(data));
            } catch (IOException | IllegalStateException ex) {
                list.remove(emitter);
            }
        }
    }

    /** Keeps idle connections alive through proxies that close silent streams. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 20_000)
    void heartbeat() {
        subscribers.values().forEach(list -> {
            for (SseEmitter emitter : list) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (IOException | IllegalStateException ex) {
                    list.remove(emitter);
                }
            }
        });
    }
}
