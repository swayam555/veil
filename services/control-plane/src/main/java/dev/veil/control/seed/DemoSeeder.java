package dev.veil.control.seed;

import dev.veil.contracts.OrgConfig;
import dev.veil.control.config.AppProperties;
import dev.veil.control.config.Caller;
import dev.veil.control.config.Crypto;
import dev.veil.control.config.JsonCodec;
import dev.veil.control.keys.KeyService;
import dev.veil.control.policy.PolicyService;
import dev.veil.control.publish.ConfigPublisher;
import dev.veil.detect.EntityType;
import dev.veil.policy.Action;
import dev.veil.policy.Policy;
import dev.veil.policy.Rule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Creates the demo organisation (Acme Fintech) on first start: teams, two console users, virtual keys with known
 * values (so the traffic simulator can use them), providers, routes, a dictionary and the recommended policy.
 */
@Component
public class DemoSeeder implements ApplicationRunner {

    public static final String ORG = "acme";
    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);

    /** Demo virtual keys: team, name, kind, rpm, daily budget (micro-USD), secret. */
    public record DemoKey(String team, String name, String kind, int rpm, long budget, String secret) {
    }

    public static final List<DemoKey> KEYS = List.of(
            new DemoKey("support", "Support reply assistant", "API", 600, 0, "vk_live_demo_support_7Hq2mX9pLw4nR8sT"),
            new DemoKey("engineering", "Engineering code assistant", "API", 600, 0, "vk_live_demo_engineering_K3vN8bQ1zY6cW2dF"),
            new DemoKey("finance", "Finance analyst workbench", "API", 120, 5_000_000, "vk_live_demo_finance_P5tJ7rL2xM9gH4kS"),
            new DemoKey("hr", "HR letter drafting", "API", 60, 2_000_000, "vk_live_demo_hr_N4cV6bX1qW8eR3tY"),
            new DemoKey("sales", "Sales email helper", "API", 300, 0, "vk_live_demo_sales_G7hJ2kL9mN4bV6cX"),
            new DemoKey("support", "Browser extension (Support)", "EXTENSION", 0, 0, "vk_ext_demo_support_B6wE3nU8yT1qA5zR"),
            new DemoKey("sales", "Browser extension (Sales)", "EXTENSION", 0, 0, "vk_ext_demo_sales_Z2xC4vB6nM8aS1dF"),
            new DemoKey("legal", "Browser extension (Legal)", "EXTENSION", 0, 0, "vk_ext_demo_legal_Q9wE7rT5yU3iO1pL"));

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final KeyService keys;
    private final PolicyService policies;
    private final ConfigPublisher publisher;
    private final Crypto crypto;
    private final JsonCodec json;
    private final AppProperties props;

    public DemoSeeder(JdbcClient jdbc, TransactionTemplate tx, KeyService keys, PolicyService policies,
                      ConfigPublisher publisher, Crypto crypto, JsonCodec json, AppProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.keys = keys;
        this.policies = policies;
        this.publisher = publisher;
        this.crypto = crypto;
        this.json = json;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.seedDemo()) return;
        tx.executeWithoutResult(s -> {
            // Several replicas may start at once: a transaction-scoped advisory lock lets exactly one of them seed.
            jdbc.sql("SELECT pg_advisory_xact_lock(4242)").query().singleRow();
            boolean exists = jdbc.sql("SELECT count(*) FROM orgs WHERE id = ?").param(ORG).query(Integer.class).single() > 0;
            if (!exists) {
                seed();
                log.info("Seeded demo organisation '{}'", ORG);
            }
        });
        tx.executeWithoutResult(s -> ensureUpstream());
    }

    private void seed() {
        jdbc.sql("INSERT INTO orgs (id, name, fingerprint_salt) VALUES (?, ?, ?)")
                .params(ORG, "Acme Fintech Pvt Ltd", Crypto.randomToken(24)).update();
        String[][] teams = {{"support", "Support", "0"}, {"engineering", "Engineering", "0"}, {"finance", "Finance", "20000000"},
                {"hr", "HR", "5000000"}, {"sales", "Sales", "0"}, {"legal", "Legal", "0"}};
        for (String[] t : teams) {
            jdbc.sql("INSERT INTO teams (id, org_id, name, daily_budget_micros) VALUES (?, ?, ?, ?)")
                    .params(t[0], ORG, t[1], Long.parseLong(t[2])).update();
        }
        BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
        jdbc.sql("INSERT INTO users (id, org_id, email, name, password_hash, role) VALUES (?, ?, ?, ?, ?, ?)")
                .params("usr_admin", ORG, "admin@acme.test", "Asha Rao", bcrypt.encode("veil-admin"), "ADMIN").update();
        jdbc.sql("INSERT INTO users (id, org_id, email, name, password_hash, role) VALUES (?, ?, ?, ?, ?, ?)")
                .params("usr_auditor", ORG, "auditor@acme.test", "Vikram Shah", bcrypt.encode("veil-auditor"), "VIEWER").update();

        Caller system = new Caller("system", ORG, "ADMIN", "Veil setup");
        for (DemoKey k : KEYS) keys.createWithSecret(system, k.name(), k.team(), k.kind(), k.rpm(), k.budget(), k.secret());

        provider("veil-echo", "Veil Echo (simulated cloud model)", OrgConfig.ProviderKind.ECHO, null, false);
        provider("veil-echo-eu", "Veil Echo, EU region (simulated backup)", OrgConfig.ProviderKind.ECHO, null, false);
        provider("onprem-llm", "On-prem model (simulated)", OrgConfig.ProviderKind.ECHO, null, true);
        route("gpt-4o-mini", List.of(new OrgConfig.RouteConfig.Target("veil-echo", "gpt-4o-mini"),
                new OrgConfig.RouteConfig.Target("veil-echo-eu", "gpt-4o-mini")), 0.15, 0.60);
        route("claude-sonnet-4-5", List.of(new OrgConfig.RouteConfig.Target("veil-echo", "claude-sonnet-4-5"),
                new OrgConfig.RouteConfig.Target("veil-echo-eu", "claude-sonnet-4-5")), 3.0, 15.0);
        route("gemini-2.5-flash", List.of(new OrgConfig.RouteConfig.Target("veil-echo", "gemini-2.5-flash")), 0.30, 2.50);
        route("local-llama", List.of(new OrgConfig.RouteConfig.Target("onprem-llm", "llama-3.1-8b-instruct")), 0, 0);

        String[][] terms = {{"Project Falcon", "Unannounced acquisition"}, {"Bluebird", "Product codename"},
                {"Zenith Capital", "Client under NDA"}, {"db-prod-01.acme.internal", "Internal hostname"},
                {"Acme Atlas", "Unreleased product"}};
        for (String[] t : terms) {
            jdbc.sql("INSERT INTO dictionary_terms (org_id, term, label) VALUES (?, ?, ?)").params(ORG, t[0], t[1]).update();
        }
        String[] tender = {
                "Our bid for the state electricity board tender is 412 crore, undercut the incumbent by 6 percent",
                "Prepare the financial bid sheet for the railway tender, our quoted rate per unit is confidential",
                "Here is the L1 price we plan to quote in the government tender next week"};
        for (String e : tender) {
            jdbc.sql("INSERT INTO topic_examples (org_id, label, description, example) VALUES (?, ?, ?, ?)")
                    .params(ORG, "tender", "Tender bid pricing", e).update();
        }

        Policy base = Policy.recommended();
        List<Rule> rules = new ArrayList<>(base.rules());
        rules.replaceAll(r -> r.id().equals("warn-source-code")
                ? new Rule(r.id(), "Flag source code outside Engineering", r.types(), r.categories(),
                Set.of("support", "finance", "hr", "sales", "legal"), r.sources(), r.minConfidence(), r.action(), true)
                : r);
        rules.add(new Rule("finance-onprem", "Finance: confidential topics stay on-prem", Set.of(EntityType.CONFIDENTIAL_TOPIC),
                Set.of(), Set.of("finance"), Set.of("api"), 0.65, Action.ROUTE_LOCAL, true));
        rules.add(new Rule("legal-browser-block", "Legal: no confidential matters in AI chat websites",
                Set.of(EntityType.CONFIDENTIAL_TOPIC), Set.of(), Set.of("legal"), Set.of("extension"), 0.7, Action.BLOCK, true));
        Policy seeded = new Policy(0, rules, Set.of(), 4096, Policy.FailMode.CLOSED, true, true, true);
        policies.publish(system, seeded, "Initial policy (Veil recommended + Acme rules)");
        publisher.publish(ORG);
    }

    /** Adds or updates the real upstream provider when one is configured through the environment. */
    private void ensureUpstream() {
        var up = props.upstream();
        if (up == null || up.baseUrl() == null || up.baseUrl().isBlank() || up.apiKey() == null || up.apiKey().isBlank()) return;
        boolean orgExists = jdbc.sql("SELECT count(*) FROM orgs WHERE id = ?").param(ORG).query(Integer.class).single() > 0;
        if (!orgExists) return;
        jdbc.sql("""
                        INSERT INTO providers (id, org_id, name, kind, base_url, api_key_ciphertext, local, enabled)
                        VALUES ('upstream', ?, 'Upstream LLM (from environment)', 'OPENAI_COMPATIBLE', ?, ?, false, true)
                        ON CONFLICT (org_id, id) DO UPDATE SET base_url = excluded.base_url, api_key_ciphertext = excluded.api_key_ciphertext""")
                .params(ORG, up.baseUrl().trim(), crypto.encrypt(up.apiKey().trim())).update();
        String model = up.model() == null || up.model().isBlank() ? "gpt-4o-mini" : up.model().trim();
        route("gpt-4o-mini", List.of(new OrgConfig.RouteConfig.Target("upstream", model),
                new OrgConfig.RouteConfig.Target("veil-echo", "gpt-4o-mini"),
                new OrgConfig.RouteConfig.Target("veil-echo-eu", "gpt-4o-mini")), 0.15, 0.60);
        publisher.publish(ORG);
        log.info("Upstream provider configured: {} ({})", up.baseUrl(), model);
    }

    private void provider(String id, String name, OrgConfig.ProviderKind kind, String url, boolean local) {
        jdbc.sql("INSERT INTO providers (id, org_id, name, kind, base_url, local, enabled) VALUES (?, ?, ?, ?, ?, ?, true)")
                .params(id, ORG, name, kind.name(), url, local).update();
    }

    private void route(String model, List<OrgConfig.RouteConfig.Target> targets, double in, double out) {
        jdbc.sql("""
                        INSERT INTO routes (org_id, model, targets, input_usd_per_mtok, output_usd_per_mtok) VALUES (?, ?, ?::jsonb, ?, ?)
                        ON CONFLICT (org_id, model) DO UPDATE SET targets = excluded.targets""")
                .params(ORG, model, json.write(targets), in, out).update();
    }
}
