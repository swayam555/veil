package dev.veil.insight;

import dev.veil.contracts.AuditEvent;
import dev.veil.contracts.Topics;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Ingestion and read APIs against real Kafka and Postgres. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InsightIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.1");

    static final String ORG = "acme";

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper json;

    RestClient admin() {
        return RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { })
                .defaultHeader("X-User-Id", "usr_admin").defaultHeader("X-User-Org", ORG).defaultHeader("X-User-Role", "ADMIN")
                .defaultHeader("X-User-Name", "Asha Rao").build();
    }

    static AuditEvent event(String id, String action, String status, String actor, List<AuditEvent.FindingSummary> findings) {
        return new AuditEvent(id, ORG, "key_1", "support", "Support", actor, "api", "CHAT", null, "gpt-4o-mini", "veil-echo",
                action, status, List.of("reason"), findings, "Reply to [PERSON_1]", 120, 100, 50, 45, 640, 900, 500, false, false, 3, Instant.now());
    }

    static AuditEvent.FindingSummary finding(String type, String category, String severity, String action) {
        return new AuditEvent.FindingSummary(type, category, severity, type, 0.95, 0, 10, "abcdef123456", action, "input");
    }

    void produce(String value) {
        Properties p = new Properties();
        p.put("bootstrap.servers", KAFKA.getBootstrapServers());
        try (var producer = new KafkaProducer<>(p, new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>(Topics.AUDIT, ORG, value));
            producer.flush();
        }
    }

    static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(200);
        }
        throw new AssertionError("Condition not met within 30 s");
    }

    long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    @Test
    void redeliveredEventsAreStoredAndCountedOnce() throws Exception {
        String id = "req_" + UUID.randomUUID();
        AuditEvent e = event(id, "REDACT", "OK", "a@acme.test", List.of(finding("EMAIL", "CONTACT", "MEDIUM", "REDACT")));
        String payload = json.writeValueAsString(e);
        produce(payload);
        produce(payload);
        produce(payload);
        await(() -> count("SELECT count(*) FROM requests WHERE id = ?", id) == 1);
        Thread.sleep(1500);
        assertThat(count("SELECT count(*) FROM requests WHERE id = ?", id)).isEqualTo(1);
        assertThat(count("SELECT coalesce(sum(count), 0) FROM finding_minute WHERE org_id = ? AND type = 'EMAIL'", ORG))
                .isGreaterThanOrEqualTo(1);
        // The partition for today exists, so the row did not land in the default partition.
        assertThat(count("SELECT count(*) FROM requests_default WHERE id = ?", id)).isZero();
    }

    @Test
    void blockedSecretsBecomeOneIncidentPerBurstWithAnExplanation() throws Exception {
        String actor = "dev-" + UUID.randomUUID().toString().substring(0, 6) + "@acme.test";
        for (int i = 0; i < 3; i++) {
            produce(json.writeValueAsString(event("req_" + UUID.randomUUID(), "BLOCK", "BLOCKED", actor,
                    List.of(finding("API_KEY", "SECRET", "CRITICAL", "BLOCK")))));
        }
        await(() -> count("SELECT coalesce(max(occurrences), 0) FROM incidents WHERE actor = ?", actor) == 3);
        assertThat(count("SELECT count(*) FROM incidents WHERE actor = ?", actor)).isEqualTo(1);
        long id = jdbc.sql("SELECT id FROM incidents WHERE actor = ?").param(actor).query(Long.class).single();

        Map<?, ?> incident = admin().post().uri("/api/insight/incidents/{id}/explain", id).retrieve().body(Map.class);
        assertThat(incident.get("severity")).isEqualTo("CRITICAL");
        assertThat((String) incident.get("explanation")).contains("What happened", "Why it matters", "What to do", "Rotate");
        assertThat(incident.get("explainedBy")).isEqualTo("template");

        Map<?, ?> updated = admin().patch().uri("/api/insight/incidents/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("status", "RESOLVED", "notes", "Key rotated")).retrieve().body(Map.class);
        assertThat(updated.get("status")).isEqualTo("RESOLVED");
        var viewer = RestClient.builder().baseUrl("http://localhost:" + port).defaultStatusHandler(HttpStatusCode::isError, (q, r) -> { })
                .defaultHeader("X-User-Id", "v").defaultHeader("X-User-Org", ORG).defaultHeader("X-User-Role", "VIEWER").build();
        assertThat(viewer.patch().uri("/api/insight/incidents/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("status", "OPEN")).retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void repeatedTopicOnlyIncidentsFoldEvenWithoutFingerprints() throws Exception {
        String actor = "legal-" + UUID.randomUUID().toString().substring(0, 6) + "@acme.test";
        for (int i = 0; i < 2; i++) {
            AuditEvent e = new AuditEvent("req_" + UUID.randomUUID(), ORG, "key_ext", "legal", "Legal", actor, "extension", "PASTE",
                    "claude.ai", null, null, "BLOCK", "BLOCKED", List.of("Blocked: confidential topic"),
                    List.of(new AuditEvent.FindingSummary("CONFIDENTIAL_TOPIC", "CONFIDENTIAL", "HIGH", "Legal dispute", 0.8, 0, 50,
                            null, "BLOCK", "input")), null, 50, 0, 0, 0, 20, 900, 0, false, false, 3, Instant.now());
            produce(json.writeValueAsString(e));
        }
        await(() -> count("SELECT coalesce(max(occurrences), 0) FROM incidents WHERE actor = ?", actor) == 2);
        assertThat(count("SELECT count(*) FROM incidents WHERE actor = ?", actor)).isEqualTo(1);
    }

    @Test
    void shadowAiSummarisesExtensionActivity() throws Exception {
        String actor = "visitor-" + UUID.randomUUID().toString().substring(0, 6) + "@acme.test";
        AuditEvent visit = new AuditEvent("req_" + UUID.randomUUID(), ORG, "key_ext", "sales", "Sales", actor, "extension", "VISIT",
                "chatgpt.com", null, null, "ALLOW", "OK", List.of(), List.of(), null, 0, 0, 0, 0, 0, 0, 0, false, false, 3, Instant.now());
        produce(json.writeValueAsString(visit));
        await(() -> count("SELECT count(*) FROM requests WHERE actor = ?", actor) == 1);
        var res = admin().get().uri("/api/insight/shadow-ai?range=7d").retrieve().toEntity(Map.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().get("sites").toString()).contains("chatgpt.com");
    }

    @Test
    void poisonMessagesGoToTheDeadLetterTopic() throws Exception {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "dlq-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (var consumer = new KafkaConsumer<>(p, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(Topics.AUDIT_DLQ));
            produce("{not json");
            String good = "req_" + UUID.randomUUID();
            produce(json.writeValueAsString(event(good, "ALLOW", "OK", "x@acme.test", List.of())));
            boolean found = false;
            long deadline = System.currentTimeMillis() + 30_000;
            while (!found && System.currentTimeMillis() < deadline) {
                for (var r : consumer.poll(Duration.ofMillis(300))) found |= r.value().equals("{not json");
            }
            assertThat(found).isTrue();
            await(() -> count("SELECT count(*) FROM requests WHERE id = ?", good) == 1);
        }
    }

    @Test
    void dashboardAuditLogComplianceAndExport() throws Exception {
        for (int i = 0; i < 5; i++) {
            produce(json.writeValueAsString(event("req_" + UUID.randomUUID(), "REDACT", "OK", "u" + i + "@acme.test",
                    List.of(finding("AADHAAR", "GOVERNMENT_ID", "HIGH", "REDACT"), finding("PERSON", "PERSONAL", "MEDIUM", "REDACT")))));
        }
        await(() -> count("SELECT count(*) FROM requests WHERE 'AADHAAR' = ANY(types)") >= 5);

        Map<?, ?> overview = admin().get().uri("/api/insight/overview?range=1h").retrieve().body(Map.class);
        Map<?, ?> totals = (Map<?, ?>) overview.get("totals");
        assertThat(((Number) totals.get("requests")).longValue()).isGreaterThanOrEqualTo(5);
        assertThat(((Number) totals.get("protected")).longValue()).isGreaterThanOrEqualTo(10);
        assertThat((List<?>) overview.get("series")).hasSizeGreaterThan(50);
        assertThat(overview.get("findingTypes").toString()).contains("Aadhaar number");

        Map<?, ?> page = admin().get().uri("/api/insight/requests?type=AADHAAR&limit=2").retrieve().body(Map.class);
        assertThat((List<?>) page.get("items")).hasSize(2);
        String cursor = (String) page.get("nextCursor");
        assertThat(cursor).contains("|");
        Map<?, ?> next = admin().get().uri("/api/insight/requests?type=AADHAAR&limit=2&cursor={c}", cursor).retrieve().body(Map.class);
        assertThat((List<?>) next.get("items")).hasSize(2);
        assertThat(((Map<?, ?>) ((List<?>) next.get("items")).getFirst()).get("id"))
                .isNotEqualTo(((Map<?, ?>) ((List<?>) page.get("items")).getFirst()).get("id"));

        String id = (String) ((Map<?, ?>) ((List<?>) page.get("items")).getFirst()).get("id");
        Map<?, ?> detail = admin().get().uri("/api/insight/requests/{id}", id).retrieve().body(Map.class);
        assertThat(detail.get("findings")).isInstanceOf(List.class);

        Map<?, ?> compliance = admin().get().uri("/api/insight/compliance?range=24h").retrieve().body(Map.class);
        assertThat(compliance.get("regulations").toString()).contains("DPDP", "Aadhaar Act");

        String csv = admin().get().uri("/api/insight/export.csv?range=24h").retrieve().body(String.class);
        assertThat(csv).startsWith("id,at,team").contains("REDACT");

        assertThat(RestClient.create("http://localhost:" + port).get().uri("/api/insight/overview").retrieve()
                .onStatus(HttpStatusCode::isError, (q, r) -> { }).toBodilessEntity().getStatusCode().value()).isEqualTo(401);
    }
}
