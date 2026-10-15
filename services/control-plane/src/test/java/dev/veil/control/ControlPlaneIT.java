package dev.veil.control;

import dev.veil.contracts.KeyEvent;
import dev.veil.contracts.KeyInfo;
import dev.veil.contracts.OrgConfig;
import dev.veil.contracts.Topics;
import dev.veil.control.config.Crypto;
import dev.veil.control.seed.DemoSeeder;
import dev.veil.policy.Action;
import dev.veil.policy.Policy;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The control plane against real Postgres and Kafka: identity, keys, versioned policies and config push. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "veil.proxy-url=http://localhost:1")
class ControlPlaneIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.1");

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper mapper;

    RestClient admin;
    RestClient viewer;
    RestClient anonymous;

    @BeforeEach
    void clients() {
        anonymous = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { }).build();
        admin = anonymous.mutate().defaultHeader("X-User-Id", "usr_admin").defaultHeader("X-User-Org", DemoSeeder.ORG)
                .defaultHeader("X-User-Role", "ADMIN").defaultHeader("X-User-Name", "Asha Rao").build();
        viewer = anonymous.mutate().defaultHeader("X-User-Id", "usr_auditor").defaultHeader("X-User-Org", DemoSeeder.ORG)
                .defaultHeader("X-User-Role", "VIEWER").build();
    }

    @Test
    void loginIssuesATokenAndLocksAfterRepeatedFailures() {
        var ok = anonymous.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", "admin@acme.test", "password", "veil-admin")).retrieve().toEntity(Map.class);
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(ok.getBody()).containsKey("accessToken");

        String email = "auditor@acme.test";
        for (int i = 0; i < 5; i++) {
            var bad = anonymous.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("email", email, "password", "wrong")).retrieve().toBodilessEntity();
            assertThat(bad.getStatusCode().value()).isEqualTo(401);
        }
        var locked = anonymous.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", "veil-auditor")).retrieve().toBodilessEntity();
        assertThat(locked.getStatusCode().value()).isEqualTo(429);
    }

    @Test
    void keysAreStoredOnlyAsHashesAndRevocationIsPushedToKafka() throws Exception {
        try (var consumer = consumer(Topics.KEYS)) {
            var created = admin.post().uri("/api/keys").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("name", "Test bot", "teamId", "support", "rpmLimit", 30, "dailyBudgetMicros", 0))
                    .retrieve().toEntity(Map.class);
            assertThat(created.getStatusCode().value()).isEqualTo(201);
            String secret = (String) created.getBody().get("secret");
            String id = (String) ((Map<?, ?>) created.getBody().get("key")).get("id");
            assertThat(secret).startsWith("vk_live_");

            // The database has the hash, never the key.
            assertThat(jdbc.sql("SELECT count(*) FROM virtual_keys WHERE key_hash = ?").param(secret).query(Integer.class).single()).isZero();
            KeyInfo info = anonymous.get().uri("/internal/keys/{h}", Crypto.sha256(secret)).retrieve().body(KeyInfo.class);
            assertThat(info.teamId()).isEqualTo("support");
            assertThat(info.revoked()).isFalse();

            assertThat(admin.post().uri("/api/keys/{id}/revoke", id).retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(200);
            assertThat(anonymous.get().uri("/internal/keys/{h}", Crypto.sha256(secret)).retrieve().body(KeyInfo.class).revoked()).isTrue();

            List<KeyEvent> events = poll(consumer, KeyEvent.class, e -> e.keyId().equals(id));
            assertThat(events).extracting(KeyEvent::change).contains("REVOKED");
        }
    }

    @Test
    void viewersCannotChangeConfiguration() {
        var r = viewer.post().uri("/api/keys").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "x", "teamId", "support")).retrieve().toBodilessEntity();
        assertThat(r.getStatusCode().value()).isEqualTo(403);
        assertThat(anonymous.get().uri("/api/keys").retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(401);
        assertThat(viewer.get().uri("/api/keys").retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void publishingAPolicyCreatesAVersionAndPushesASnapshot() throws Exception {
        try (var consumer = consumer(Topics.CONFIG)) {
            Policy current = admin.get().uri("/api/policy").retrieve().body(Policy.class);
            assertThat(current.rules()).isNotEmpty();
            Policy draft = new Policy(0, current.rules(), java.util.Set.of("gpt-4o-mini"), 2048, Policy.FailMode.OPEN,
                    false, true, true);
            Policy published = admin.put().uri("/api/policy?note=tighten models").contentType(MediaType.APPLICATION_JSON)
                    .body(draft).retrieve().body(Policy.class);
            assertThat(published.version()).isGreaterThan(current.version());

            List<OrgConfig> snapshots = poll(consumer, OrgConfig.class, c -> c.policy().version() == published.version());
            OrgConfig snap = snapshots.getLast();
            assertThat(snap.policy().allowedModels()).containsExactly("gpt-4o-mini");
            assertThat(snap.routes()).extracting(OrgConfig.RouteConfig::model).contains("gpt-4o-mini", "local-llama");
            assertThat(snap.dictionary()).extracting(OrgConfig.DictionaryTerm::term).contains("Project Falcon");

            // Rollback creates a new version with the old content.
            Policy rolled = admin.post().uri("/api/policy/versions/{v}/rollback", current.version()).retrieve().body(Policy.class);
            assertThat(rolled.version()).isEqualTo(published.version() + 1);
            assertThat(rolled.allowedModels()).isEqualTo(current.allowedModels());
        }
    }

    @Test
    void invalidPoliciesAreRejected() {
        Policy dup = new Policy(0, List.of(dev.veil.policy.Rule.of("a", "A", java.util.Set.of(), Action.BLOCK, 0.5),
                dev.veil.policy.Rule.of("a", "B", java.util.Set.of(), Action.BLOCK, 0.5)), null, 0, null, false, false, false);
        var r = admin.put().uri("/api/policy").contentType(MediaType.APPLICATION_JSON).body(dup).retrieve().toBodilessEntity();
        assertThat(r.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void providerKeysAreEncryptedAndNeverReturned() {
        String id = "test-" + UUID.randomUUID().toString().substring(0, 6);
        var r = admin.put().uri("/api/providers/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "Groq", "kind", "OPENAI_COMPATIBLE", "baseUrl", "https://api.groq.com/openai/v1",
                        "apiKey", "gsk_supersecretvalue123", "local", false, "enabled", true))
                .retrieve().toEntity(String.class);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).doesNotContain("gsk_supersecretvalue123").contains("\"hasApiKey\":true");
        String stored = jdbc.sql("SELECT api_key_ciphertext FROM providers WHERE id = ?").param(id).query(String.class).single();
        assertThat(stored).doesNotContain("gsk_");
        assertThat(admin.get().uri("/api/providers").retrieve().body(String.class)).doesNotContain("gsk_");
    }

    @Test
    void copilotDraftsRulesFromPlainEnglishWithoutAnLlm() {
        var draft = admin.post().uri("/api/policy/copilot").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("instruction", "Block API keys and passwords everywhere. Redact Aadhaar and PAN for the Support team; "
                        + "keep confidential topics on-prem for Finance. Warn when employees paste source code in the browser."))
                .retrieve().body(Map.class);
        assertThat(draft.get("mode")).isEqualTo("rules");
        List<Map<String, Object>> rules = (List<Map<String, Object>>) draft.get("rules");
        assertThat(rules).extracting(r -> r.get("action")).containsExactly("BLOCK", "REDACT", "ROUTE_LOCAL", "WARN");
        assertThat((List<String>) rules.get(1).get("teams")).containsExactly("support");
        assertThat((List<String>) rules.get(1).get("types")).containsExactlyInAnyOrder("AADHAAR", "PAN");
        assertThat((List<String>) rules.get(2).get("teams")).containsExactly("finance");
        assertThat((List<String>) rules.get(3).get("sources")).containsExactly("extension");
        assertThat((List<String>) rules.get(0).get("sources")).isEmpty();
    }

    private KafkaConsumer<String, String> consumer(String topic) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        var c = new KafkaConsumer<>(p, new StringDeserializer(), new StringDeserializer());
        c.subscribe(List.of(topic));
        return c;
    }

    private <T> List<T> poll(KafkaConsumer<String, String> consumer, Class<T> type, java.util.function.Predicate<T> until) {
        List<T> out = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            for (var rec : consumer.poll(Duration.ofMillis(300))) {
                T value = mapper.readValue(rec.value(), type);
                out.add(value);
                if (until.test(value)) return out;
            }
        }
        throw new AssertionError("Timed out waiting for " + type.getSimpleName() + "; got " + out.size());
    }
}
