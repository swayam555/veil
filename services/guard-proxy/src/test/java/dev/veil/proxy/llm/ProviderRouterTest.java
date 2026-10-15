package dev.veil.proxy.llm;

import dev.veil.contracts.OrgConfig;
import dev.veil.detect.ScanEngine;
import dev.veil.policy.Policy;
import dev.veil.proxy.config.ProxyProperties;
import dev.veil.proxy.state.OrgState;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Failover and circuit breaking with the built-in Echo providers and fault injection. */
class ProviderRouterTest {

    static final ProxyProperties PROPS = new ProxyProperties("3q2+7wAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "http://x",
            Duration.ofSeconds(1), Duration.ofSeconds(5), 0.06, Duration.ofHours(1), false, 100_000, "test");

    static OrgState org() {
        var a = new OrgConfig.ProviderConfig("a", "Primary", OrgConfig.ProviderKind.ECHO, null, null, false, true);
        var b = new OrgConfig.ProviderConfig("b", "Backup", OrgConfig.ProviderKind.ECHO, null, null, false, true);
        var local = new OrgConfig.ProviderConfig("l", "Local", OrgConfig.ProviderKind.ECHO, null, null, true, true);
        var cfg = new OrgConfig("o", "Org", 1, Policy.allowAll(), List.of(), List.of(), List.of(a, b, local),
                List.of(new OrgConfig.RouteConfig("m", List.of(new OrgConfig.RouteConfig.Target("a", "m1"), new OrgConfig.RouteConfig.Target("b", "m2")), 1, 1),
                        new OrgConfig.RouteConfig("local", List.of(new OrgConfig.RouteConfig.Target("l", "llama")), 0, 0)),
                Map.of(), "salt", Instant.now());
        return new OrgState(cfg, ScanEngine.standard(), ScanEngine.standard(), Map.of());
    }

    static ObjectNode body() {
        ObjectNode b = JsonNodeFactory.instance.objectNode();
        b.put("model", "m");
        b.putArray("messages").addObject().put("role", "user").put("content", "hello");
        return b;
    }

    @Test
    void failsOverAndOpensTheCircuitAfterRepeatedFailures() {
        ProviderFaults faults = new ProviderFaults();
        ProviderRouter router = new ProviderRouter(List.of(new EchoProvider(faults)), PROPS);
        OrgState org = org();
        faults.set("a", new ProviderFaults.Fault(1, 0));
        var targets = router.targets(org, "m", false);
        for (int i = 0; i < 6; i++) {
            var r = router.complete(org, targets, body()).block();
            assertThat(r.servedBy().provider().id()).isEqualTo("b");
        }
        assertThat(router.breakerStates()).containsEntry("o:a", "OPEN").containsEntry("o:b", "CLOSED");
        var skipped = router.complete(org, targets, body()).block();
        assertThat(skipped.attempts().getFirst().outcome()).isEqualTo("circuit open (skipped)");
    }

    @Test
    void streamsFailOverBeforeTheFirstChunk() {
        ProviderFaults faults = new ProviderFaults();
        ProviderRouter router = new ProviderRouter(List.of(new EchoProvider(faults)), PROPS);
        OrgState org = org();
        faults.set("a", new ProviderFaults.Fault(1, 0));
        var routed = router.stream(org, router.targets(org, "m", false), body());
        var chunks = routed.flux().collectList().block();
        assertThat(chunks).isNotEmpty();
        assertThat(routed.servedBy().get().provider().id()).isEqualTo("b");
    }

    @Test
    void localOnlyUsesOnPremisesProviders() {
        ProviderRouter router = new ProviderRouter(List.of(new EchoProvider(new ProviderFaults())), PROPS);
        var targets = router.targets(org(), "m", true);
        assertThat(targets).extracting(t -> t.provider().id()).containsExactly("l");
    }
}
