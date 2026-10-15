package dev.veil.proxy.audit;

import dev.veil.contracts.AuditEvent;
import dev.veil.detect.Checksums;
import dev.veil.detect.Finding;
import dev.veil.policy.Decision;
import dev.veil.proxy.config.Crypto;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Turns findings into audit summaries: types, offsets and salted fingerprints, never the values. */
public final class Audits {

    private Audits() {
    }

    public static List<AuditEvent.FindingSummary> summaries(Decision decision, String salt, String location) {
        List<AuditEvent.FindingSummary> out = new ArrayList<>();
        for (Decision.FindingDecision fd : decision.findings()) out.add(summary(fd.finding(), fd.action().name(), salt, location));
        return out;
    }

    public static AuditEvent.FindingSummary summary(Finding f, String action, String salt, String location) {
        String fp = f.type().spanBased() ? Crypto.fingerprint(salt == null ? "veil" : salt, normalise(f)) : null;
        return new AuditEvent.FindingSummary(f.type().name(), f.type().category().name(), f.type().severity().name(), f.detail(),
                Math.round(f.confidence() * 100) / 100.0, f.start(), f.end(), fp, action, location);
    }

    private static String normalise(Finding f) {
        String v = f.value().strip();
        return switch (f.type()) {
            case PHONE, AADHAAR, CREDIT_CARD, US_SSN, BANK_ACCOUNT -> Checksums.digitsOnly(v);
            case EMAIL, PERSON, CUSTOM_TERM -> v.toLowerCase(Locale.ROOT);
            case IBAN -> v.replace(" ", "").toUpperCase(Locale.ROOT);
            default -> v;
        };
    }
}
