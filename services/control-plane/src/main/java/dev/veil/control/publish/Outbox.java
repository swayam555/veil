package dev.veil.control.publish;

import dev.veil.control.config.JsonCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Transactional outbox: an event is written in the same transaction as the change that caused it (a new policy
 * version, a revoked key) and relayed to Kafka afterwards. A crash can delay a config push, never lose it, and a
 * policy can never be live in Kafka without being saved in Postgres.
 */
@Component
public class Outbox {

    private static final Logger log = LoggerFactory.getLogger(Outbox.class);

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final JsonCodec json;

    public Outbox(JdbcClient jdbc, KafkaTemplate<String, String> kafka, JsonCodec json) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void add(String topic, String key, Object event) {
        jdbc.sql("INSERT INTO outbox_events (topic, event_key, payload) VALUES (?, ?, ?)")
                .params(topic, key, json.write(event)).update();
    }

    @Scheduled(fixedDelay = 150)
    @Transactional
    public void relay() {
        List<Row> batch = jdbc.sql("""
                SELECT id, topic, event_key, payload FROM outbox_events WHERE published_at IS NULL
                ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED""").query(Row.class).list();
        if (batch.isEmpty()) return;
        List<CompletableFuture<?>> sends = new ArrayList<>();
        batch.forEach(r -> sends.add(kafka.send(r.topic(), r.eventKey(), r.payload())));
        try {
            CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Kafka publish failed; batch will be retried", e);
        }
        jdbc.sql("UPDATE outbox_events SET published_at = now() WHERE id IN (:ids)")
                .param("ids", batch.stream().map(Row::id).toList()).update();
        log.debug("Relayed {} events", batch.size());
    }

    @Scheduled(fixedDelay = 600_000)
    public void purge() {
        jdbc.sql("DELETE FROM outbox_events WHERE published_at < now() - interval '6 hours'").update();
    }

    record Row(long id, String topic, String eventKey, String payload) {
    }
}
