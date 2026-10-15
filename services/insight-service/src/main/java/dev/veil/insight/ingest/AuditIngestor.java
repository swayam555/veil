package dev.veil.insight.ingest;

import dev.veil.contracts.AuditEvent;
import dev.veil.contracts.Topics;
import dev.veil.insight.config.JsonCodec;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Consumes audit events in batches. The whole batch is stored in one transaction; if that fails, events are retried
 * one by one so a single malformed or unstorable event goes to the dead-letter topic instead of blocking the
 * partition forever.
 */
@Component
public class AuditIngestor {

    private static final Logger log = LoggerFactory.getLogger(AuditIngestor.class);

    private final AuditStore store;
    private final JsonCodec json;
    private final KafkaTemplate<String, String> kafka;
    private final Counter stored;
    private final Counter duplicates;
    private final Counter deadLettered;

    public AuditIngestor(AuditStore store, JsonCodec json, KafkaTemplate<String, String> kafka, MeterRegistry meters) {
        this.store = store;
        this.json = json;
        this.kafka = kafka;
        this.stored = Counter.builder("veil.insight.stored").register(meters);
        this.duplicates = Counter.builder("veil.insight.duplicates").register(meters);
        this.deadLettered = Counter.builder("veil.insight.dead_lettered").register(meters);
    }

    @KafkaListener(topics = Topics.AUDIT, containerFactory = "auditBatchFactory")
    public void onBatch(List<ConsumerRecord<String, String>> records) {
        List<AuditEvent> events = new ArrayList<>(records.size());
        List<ConsumerRecord<String, String>> poison = new ArrayList<>();
        for (var r : records) {
            try {
                events.add(json.read(r.value(), AuditEvent.class));
            } catch (RuntimeException e) {
                poison.add(r);
            }
        }
        poison.forEach(r -> deadLetter(r.key(), r.value(), "unparseable"));
        try {
            count(events.size(), store.store(events));
        } catch (RuntimeException batchFailure) {
            log.warn("Batch of {} failed ({}); storing one by one", events.size(), batchFailure.toString());
            for (AuditEvent e : events) {
                try {
                    count(1, store.store(List.of(e)));
                } catch (RuntimeException single) {
                    log.error("Audit event {} could not be stored; sending to DLQ", e.id(), single);
                    deadLetter(e.orgId(), json.write(e), single.getClass().getSimpleName());
                }
            }
        }
    }

    private void count(int total, int inserted) {
        stored.increment(inserted);
        duplicates.increment(total - inserted);
    }

    private void deadLetter(String key, String value, String reason) {
        try {
            kafka.send(Topics.AUDIT_DLQ, key, value).get(10, TimeUnit.SECONDS);
            deadLettered.increment();
        } catch (Exception e) {
            // Rethrowing makes the container retry the batch; better than silently losing an audit event.
            throw new IllegalStateException("Could not dead-letter an audit event (" + reason + ")", e);
        }
    }
}
