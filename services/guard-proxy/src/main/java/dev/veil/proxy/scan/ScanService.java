package dev.veil.proxy.scan;

import dev.veil.contracts.AuditEvent;
import dev.veil.contracts.KeyInfo;
import dev.veil.detect.Finding;
import dev.veil.detect.RedactionVault;
import dev.veil.policy.Action;
import dev.veil.policy.Decision;
import dev.veil.policy.Policy;
import dev.veil.policy.PolicyEngine;
import dev.veil.proxy.audit.AuditPublisher;
import dev.veil.proxy.audit.Audits;
import dev.veil.proxy.auth.ProxyException;
import dev.veil.proxy.config.ProxyProperties;
import dev.veil.proxy.detect.DetectionService;
import dev.veil.proxy.state.ConfigStore;
import dev.veil.proxy.state.OrgState;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Detection and a policy decision for a piece of text, without calling any model: used by the Scan API, the browser
 * extension, and the console's playground and policy dry-run.
 */
@Service
public class ScanService {

    private final ConfigStore configs;
    private final DetectionService detection;
    private final AuditPublisher audit;
    private final ProxyProperties props;

    public ScanService(ConfigStore configs, DetectionService detection, AuditPublisher audit, ProxyProperties props) {
        this.configs = configs;
        this.detection = detection;
        this.audit = audit;
        this.props = props;
    }

    /**
     * @param value only included when the caller is allowed to see it (the console); the Scan API returns offsets
     */
    public record FindingView(String type, String label, String category, String severity, String detail, double confidence,
                              int start, int end, String action, String rule, String value) {
    }

    public record Result(String requestId, String action, List<String> reasons, List<FindingView> findings, String redactedText,
                         Map<String, Long> detectorMicros, long micros, int policyVersion, boolean degraded) {
    }

    public OrgState org(String orgId) {
        return configs.get(orgId).orElseThrow(() -> new ProxyException(HttpStatus.SERVICE_UNAVAILABLE, "server_error",
                "config_not_loaded", "Veil is still loading your organisation's policy; retry in a few seconds"));
    }

    public Mono<Result> scan(KeyInfo key, String text, String source, Policy policyOverride, boolean includeValues) {
        if (text == null) return Mono.error(ProxyException.badRequest("'text' is required"));
        if (text.length() > props.maxBodyChars()) return Mono.error(ProxyException.badRequest("Text is longer than " + props.maxBodyChars() + " characters"));
        OrgState org = org(key.orgId());
        Policy policy = policyOverride != null ? policyOverride : org.config().policy();
        return detection.scan(org, List.of(text)).map(scan -> {
            Decision d = PolicyEngine.decide(policy, scan.all(), new PolicyEngine.Context(key.teamId(), source, null));
            String redacted = new RedactionVault().redact(text, d.toRedact());
            List<FindingView> views = new ArrayList<>();
            for (Decision.FindingDecision fd : d.findings()) {
                Finding f = fd.finding();
                views.add(new FindingView(f.type().name(), f.type().label(), f.type().category().name(), f.type().severity().name(),
                        f.detail(), Math.round(f.confidence() * 100) / 100.0, f.start(), f.end(), fd.action().name(), fd.ruleId(),
                        includeValues && f.type().spanBased() ? f.value() : null));
            }
            return new Result("req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20), d.action().name(),
                    d.reasons(), views, redacted, new LinkedHashMap<>(scan.detectorMicros()), scan.micros(), policy.version(), scan.degraded());
        });
    }

    /** Audits a scan (Scan API or extension). Values are reduced to fingerprints; the redacted text may be kept. */
    public void audit(KeyInfo key, Result r, String source, String kind, String site, String actor, String text, long latencyMs) {
        OrgState org = org(key.orgId());
        String salt = org.config().fingerprintSalt();
        List<AuditEvent.FindingSummary> findings = r.findings().stream().map(f -> new AuditEvent.FindingSummary(f.type(), f.category(),
                f.severity(), f.detail(), f.confidence(), f.start(), f.end(),
                f.end() > f.start() && text != null && f.end() <= text.length() && !f.type().equals("SOURCE_CODE")
                        && !f.type().equals("CONFIDENTIAL_TOPIC") && !f.type().equals("PROMPT_INJECTION")
                        ? dev.veil.proxy.config.Crypto.fingerprint(salt, text.substring(f.start(), f.end()).strip().toLowerCase()) : null,
                f.action(), "input")).toList();
        String status = Action.BLOCK.name().equals(r.action()) ? "BLOCKED" : "OK";
        String stored = org.config().policy().storeRedactedPrompts() && text != null ? fullyMasked(text, r) : null;
        audit.publish(new AuditEvent(r.requestId(), key.orgId(), key.keyId(), key.teamId(), key.teamName(), actor, source, kind, site,
                null, null, r.action(), status, r.reasons(), findings, stored, text == null ? 0 : text.length(), 0, 0, 0,
                latencyMs, r.micros(), 0, false, false, r.policyVersion(), Instant.now()));
    }

    /** For the audit log every span finding is masked, even ones the policy allowed, so no value is ever stored. */
    private static String fullyMasked(String text, Result r) {
        StringBuilder b = new StringBuilder(text);
        r.findings().stream().filter(f -> f.end() > f.start() && !f.type().equals("SOURCE_CODE") && !f.type().equals("CONFIDENTIAL_TOPIC")
                        && !f.type().equals("PROMPT_INJECTION"))
                .sorted((a, c) -> Integer.compare(c.start(), a.start()))
                .forEach(f -> b.replace(f.start(), f.end(), "[" + f.type() + "]"));
        String s = b.toString();
        return s.length() > 4000 ? s.substring(0, 4000) + "…" : s;
    }

    public void auditVisit(KeyInfo key, String site, String actor) {
        int version = org(key.orgId()).config().policy().version();
        audit.publish(new AuditEvent("req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20), key.orgId(), key.keyId(),
                key.teamId(), key.teamName(), actor, "extension", "VISIT", site, null, null, "ALLOW", "OK", List.of(), List.of(),
                null, 0, 0, 0, 0, 0, 0, 0, false, false, version, Instant.now()));
    }
}
