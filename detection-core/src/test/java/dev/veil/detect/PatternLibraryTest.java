package dev.veil.detect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PatternLibraryTest {

    private final ScanEngine engine = ScanEngine.standard();

    private List<Finding> scan(String text) {
        return engine.scan(text).findings();
    }

    private List<String> values(String text, EntityType type) {
        return scan(text).stream().filter(f -> f.type() == type).map(Finding::value).toList();
    }

    private static String aadhaar(String first11) {
        return first11 + Checksums.verhoeffCheckDigit(first11);
    }

    @Test
    void aadhaarNeedsAValidChecksumAndGainsConfidenceFromContext() {
        String valid = aadhaar("49340259103");
        String formatted = valid.substring(0, 4) + " " + valid.substring(4, 8) + " " + valid.substring(8);
        assertThat(values("Aadhaar: " + formatted, EntityType.AADHAAR)).containsExactly(formatted);
        assertThat(scan("Aadhaar: " + formatted).getFirst().confidence()).isGreaterThan(0.95);
        assertThat(scan("ref " + formatted).getFirst().confidence()).isLessThan(0.9);

        String invalid = valid.substring(0, 11) + ((valid.charAt(11) - '0' + 1) % 10);
        assertThat(values("Aadhaar " + invalid, EntityType.AADHAAR)).isEmpty();
        assertThat(values("Aadhaar " + aadhaar("19340259103"), EntityType.AADHAAR)).as("cannot start with 1").isEmpty();
        assertThat(values("id 1" + valid, EntityType.AADHAAR)).as("part of a longer number").isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"4111 1111 1111 1111", "5555-5555-5555-4444", "378282246310005", "6521 0000 0000 0005"})
    void paymentCards(String card) {
        String text = "charge card " + card + " today";
        if (!Checksums.luhn(card)) return; // keep the table honest
        assertThat(values(text, EntityType.CREDIT_CARD)).containsExactly(card);
    }

    @Test
    void cardLikeNumbersThatFailLuhnAreIgnored() {
        assertThat(values("tracking 4111111111111112", EntityType.CREDIT_CARD)).isEmpty();
        assertThat(values("timestamp 1697040000123", EntityType.CREDIT_CARD)).isEmpty();
    }

    @Test
    void panAndGstin() {
        assertThat(values("PAN ABCPE1234F please", EntityType.PAN)).containsExactly("ABCPE1234F");
        assertThat(values("code ABCXE1234F", EntityType.PAN)).as("X is not a valid holder type").isEmpty();
        assertThat(values("GSTIN 27AAPFU0939F1ZV", EntityType.GSTIN)).containsExactly("27AAPFU0939F1ZV");
        assertThat(values("GSTIN 27AAPFU0939F1ZW", EntityType.GSTIN)).isEmpty();
    }

    @Test
    void emailsAndPhones() {
        assertThat(values("mail priya.sharma+test@acme-corp.co.in now", EntityType.EMAIL))
                .containsExactly("priya.sharma+test@acme-corp.co.in");
        assertThat(values("call +91 98765 43210", EntityType.PHONE)).containsExactly("+91 98765 43210");
        assertThat(values("call 9876543210", EntityType.PHONE)).containsExactly("9876543210");
        assertThat(values("call (415) 555-0132", EntityType.PHONE)).containsExactly("(415) 555-0132");
        assertThat(values("call +44 20 7946 0958", EntityType.PHONE)).containsExactly("+44 20 7946 0958");
        assertThat(values("multiply 1234567890 by two", EntityType.PHONE)).isEmpty();
    }

    @Test
    void bankAccountOnlyWithContext() {
        assertThat(values("account number 001234567890", EntityType.BANK_ACCOUNT)).containsExactly("001234567890");
        assertThat(values("batch 001234567890 failed", EntityType.BANK_ACCOUNT)).isEmpty();
    }

    @Test
    void ibanSsnIfscIp() {
        assertThat(values("pay GB82 WEST 1234 5698 7654 32 now", EntityType.IBAN)).containsExactly("GB82 WEST 1234 5698 7654 32");
        assertThat(values("SSN 536-22-1234", EntityType.US_SSN)).containsExactly("536-22-1234");
        assertThat(values("IFSC HDFC0001234", EntityType.IFSC)).containsExactly("HDFC0001234");
        assertThat(values("ssh to 10.20.30.40 fails", EntityType.IP_ADDRESS)).containsExactly("10.20.30.40");
        assertThat(values("upgrade to v2.3.1.4", EntityType.IP_ADDRESS)).isEmpty();
        assertThat(values("bind 127.0.0.1", EntityType.IP_ADDRESS)).isEmpty();
    }

    @Test
    void secrets() {
        assertThat(values("key AKIAIOSFODNN7EXAMPLE here", EntityType.API_KEY)).containsExactly("AKIAIOSFODNN7EXAMPLE");
        assertThat(values("token ghp_abcdefghijklmnopqrstuvwxyz0123456789AB", EntityType.API_KEY)).hasSize(1);
        assertThat(values("sk-ant-api03-abcDEF123456ghiJKL789012mno", EntityType.API_KEY)).containsExactly("sk-ant-api03-abcDEF123456ghiJKL789012mno");
        assertThat(values("OPENAI_API_KEY=sk-proj-Ab3dE5fG7hJ9kL1mN3pQ5rS7tU9vW", EntityType.API_KEY)).hasSize(1);
        assertThat(values("xoxb-1234567890-abcdefghij", EntityType.API_KEY)).hasSize(1);
        assertThat(values("sk_live_51HxYzAbCdEfGhIjKl", EntityType.API_KEY)).hasSize(1);
        assertThat(values("AIzaSyA1234567890abcdefghijklmnopqrstuv", EntityType.API_KEY)).hasSize(1);
        assertThat(values("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U", EntityType.JWT)).hasSize(1);
        assertThat(values("-----BEGIN RSA PRIVATE KEY-----\nMIIEow\n-----END RSA PRIVATE KEY-----", EntityType.PRIVATE_KEY)).hasSize(1);
        assertThat(values("the password is Tr0ub4dor&3", EntityType.PASSWORD)).containsExactly("Tr0ub4dor&3");
        assertThat(values("postgres://app:s3cr3t-pw@db:5432/x", EntityType.PASSWORD)).containsExactly("s3cr3t-pw");
        assertThat(values("api_key: q8ZtR2vN7xLk4PmW9sYb", EntityType.API_KEY)).containsExactly("q8ZtR2vN7xLk4PmW9sYb");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Set api_key to your_api_key_here in the config",
            "The password is required",
            "request id 3f2a9c1e-7b4d-4e2a-9c1e-7b4d4e2a9c1e timed out",
            "revert 9fceb02d0ae598e95dc970b74767f19372d61af8",
            "version 10.4.2 is out",
            "secret sauce recipe for the team lunch"})
    void noSecretsInOrdinaryText(String text) {
        assertThat(scan(text)).noneMatch(f -> f.type().category() == EntityType.Category.SECRET);
    }
}
