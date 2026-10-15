package dev.veil.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VeilClientTest {

    HttpServer server;
    final AtomicReference<String> auth = new AtomicReference<>();
    volatile int status = 200;
    volatile String response;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/scan", ex -> {
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] b = response.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private VeilClient client() {
        return VeilClient.builder("http://127.0.0.1:" + server.getAddress().getPort() + "/", "vk_live_test").build();
    }

    @Test
    void parsesFindingsAndRedactedText() {
        response = """
                {"requestId":"req_1","action":"REDACT","reasons":["Redacted: email"],"findings":[{"type":"EMAIL","label":"Email address",
                "category":"CONTACT","severity":"MEDIUM","detail":"Email address","confidence":0.99,"start":5,"end":15,"action":"REDACT",
                "rule":"redact-contact","value":null}],"redactedText":"mail [EMAIL_1]","detectorMicros":{},"micros":120,"policyVersion":3,"degraded":false}""";
        var r = client().scan("mail a@b.co.in");
        assertThat(auth.get()).isEqualTo("Bearer vk_live_test");
        assertThat(r.findings()).extracting(VeilClient.Finding::type).containsExactly("EMAIL");
        assertThat(r.blocked()).isFalse();
        assertThat(client().redact("mail a@b.co.in")).isEqualTo("mail [EMAIL_1]");
    }

    @Test
    void blockedTextAndHttpErrorsThrow() {
        response = """
                {"requestId":"req_2","action":"BLOCK","reasons":["Blocked: api key"],"findings":[],"redactedText":"x","policyVersion":3}""";
        assertThatThrownBy(() -> client().redact("AKIA...")).isInstanceOf(VeilClient.VeilException.class).hasMessageContaining("Blocked");
        status = 401;
        response = "{\"error\":{\"message\":\"Invalid API key\"}}";
        assertThatThrownBy(() -> client().scan("x")).isInstanceOfSatisfying(VeilClient.VeilException.class,
                e -> assertThat(e.status()).isEqualTo(401));
    }
}
