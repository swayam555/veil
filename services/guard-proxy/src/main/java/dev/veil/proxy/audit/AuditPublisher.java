package dev.veil.proxy.audit;

import dev.veil.contracts.AuditEvent;
import dev.veil.contracts.Topics;
import dev.veil.proxy.config.JsonCodec;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Publishes audit events to Kafka after the response, so auditing adds no latency. If Kafka is unavailable, events
 * wait in a bounded buffer and are retried; requests keep flowing. Only if the buffer overflows (Kafka down for a
 * long time under load) are events dropped, and that is counted and alerted on.
 */
@Component
public class AuditPublisher {

    private static final Logger log = LoggerFactory.getLogger(AuditPublisher.class);
    private static final int BUFFER = 20_000;

    private final KafkaTemplate<String, String> kafka;
    private final JsonCodec json;
    private final BlockingQueue<AuditEvent> buffer = new ArrayBlockingQueue<>(BUFFER);
    /** Sends the Kafka client still holds (it queues records itself while the broker is down, until delivery timeout). */
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Counter published;
    private final Counter dropped;

    public AuditPublisher(KafkaTemplate<String, String> kafka, JsonCodec json, MeterRegistry meters) {
        this.kafka = kafka;
        this.json = json;
        this.published = Counter.builder("veil.audit.published").register(meters);
        this.dropped = Counter.builder("veil.audit.dropped").register(meters);
        Gauge.builder("veil.audit.buffered", this, AuditPublisher::buffered).register(meters);
    }

    /** Never blocks the caller: sending happens on a worker thread (Kafka's send can block on metadata). */
    public void publish(AuditEvent event) {
        reactor.core.scheduler.Schedulers.boundedElastic().schedule(() -> send(event));
    }

    private void send(AuditEvent event) {
        inFlight.incrementAndGet();
        try {
            kafka.send(Topics.AUDIT, event.orgId(), json.write(event)).whenComplete((r, e) -> {
                inFlight.decrementAndGet();
                if (e == null) published.increment();
                else park(event, e);
            });
        } catch (RuntimeException e) {
            inFlight.decrementAndGet();
            park(event, e);
        }
    }

    private void park(AuditEvent event, Throwable cause) {
        if (buffer.offer(event)) {
            log.debug("Audit event buffered ({}): {}", buffer.size(), cause.toString());
        } else {
            dropped.increment();
            log.error("Audit buffer full; dropped event {}", event.id());
        }
    }

    @Scheduled(fixedDelay = 2000)
    void retry() {
        int n = buffer.size();
        for (int i = 0; i < n; i++) {
            AuditEvent e;
            try {
                e = buffer.poll(0, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            if (e == null) return;
            send(e);
        }
    }

    public int buffered() {
        return buffer.size() + inFlight.get();
    }
}
