package dev.veil.detect;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Everything Veil can find. Span types point at exact characters and can be redacted; document types
 * ({@link #spanBased()} false) describe the text as a whole and can only be allowed, flagged, routed or blocked.
 */
public enum EntityType {

    EMAIL("Email address", Category.CONTACT, Severity.MEDIUM, true, "DPDP", "GDPR"),
    PHONE("Phone number", Category.CONTACT, Severity.MEDIUM, true, "DPDP", "GDPR"),
    PERSON("Person name", Category.PERSONAL, Severity.MEDIUM, true, "DPDP", "GDPR", "HIPAA"),
    AADHAAR("Aadhaar number", Category.GOVERNMENT_ID, Severity.HIGH, true, "DPDP", "Aadhaar Act"),
    PAN("PAN", Category.GOVERNMENT_ID, Severity.HIGH, true, "DPDP"),
    US_SSN("US Social Security number", Category.GOVERNMENT_ID, Severity.HIGH, true, "GDPR", "HIPAA", "GLBA"),
    GSTIN("GSTIN", Category.FINANCIAL, Severity.LOW, true, "DPDP"),
    CREDIT_CARD("Payment card number", Category.FINANCIAL, Severity.HIGH, true, "PCI DSS", "DPDP", "GDPR"),
    IBAN("IBAN", Category.FINANCIAL, Severity.HIGH, true, "GDPR", "PSD2"),
    IFSC("IFSC code", Category.FINANCIAL, Severity.LOW, true, "DPDP"),
    BANK_ACCOUNT("Bank account number", Category.FINANCIAL, Severity.HIGH, true, "DPDP", "GDPR"),
    IP_ADDRESS("IP address", Category.TECHNICAL, Severity.LOW, true, "GDPR"),
    API_KEY("API key or token", Category.SECRET, Severity.CRITICAL, true, "SOC 2", "ISO 27001"),
    PRIVATE_KEY("Private key", Category.SECRET, Severity.CRITICAL, true, "SOC 2", "ISO 27001"),
    PASSWORD("Password", Category.SECRET, Severity.CRITICAL, true, "SOC 2", "ISO 27001"),
    JWT("Session token (JWT)", Category.SECRET, Severity.HIGH, true, "SOC 2"),
    CUSTOM_TERM("Company term", Category.CONFIDENTIAL, Severity.HIGH, true, "Trade secrets"),
    SOURCE_CODE("Source code", Category.CONFIDENTIAL, Severity.MEDIUM, false, "Trade secrets"),
    CONFIDENTIAL_TOPIC("Confidential topic", Category.CONFIDENTIAL, Severity.HIGH, false, "Trade secrets", "SEBI PIT"),
    PROMPT_INJECTION("Prompt injection", Category.THREAT, Severity.HIGH, false, "OWASP LLM01");

    public enum Category { CONTACT, PERSONAL, GOVERNMENT_ID, FINANCIAL, TECHNICAL, SECRET, CONFIDENTIAL, THREAT }

    public enum Severity { LOW, MEDIUM, HIGH, CRITICAL }

    private final String label;
    private final Category category;
    private final Severity severity;
    private final boolean spanBased;
    private final List<String> regulations;

    EntityType(String label, Category category, Severity severity, boolean spanBased, String... regulations) {
        this.label = label;
        this.category = category;
        this.severity = severity;
        this.spanBased = spanBased;
        this.regulations = List.of(regulations);
    }

    public String label() {
        return label;
    }

    public Category category() {
        return category;
    }

    public Severity severity() {
        return severity;
    }

    /** True when findings point at characters that can be redacted. */
    public boolean spanBased() {
        return spanBased;
    }

    public List<String> regulations() {
        return regulations;
    }

    /** Placeholder stem used in redacted text, e.g. {@code [EMAIL_1]}. */
    public String placeholderStem() {
        return name();
    }

    public static Set<EntityType> ofCategory(Category c) {
        return Set.copyOf(Arrays.stream(values()).filter(t -> t.category == c).toList());
    }
}
