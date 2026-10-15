package dev.veil.detect;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class RedactionTest {

    private final ScanEngine engine = ScanEngine.standard();

    @Test
    void sameValueSamePlaceholderAcrossMessagesAndRoundTrip() {
        RedactionVault vault = new RedactionVault();
        String m1 = "Customer Priya Sharma (priya.sharma@acme.in) wants a refund.";
        String m2 = "Reply to PRIYA.SHARMA@acme.in and cc ravi@acme.in.";
        String r1 = vault.redact(m1, engine.scan(m1).findings());
        String r2 = vault.redact(m2, engine.scan(m2).findings());
        assertThat(r1).isEqualTo("Customer [PERSON_1] ([EMAIL_1]) wants a refund.");
        assertThat(r2).isEqualTo("Reply to [EMAIL_1] and cc [EMAIL_2].");
        assertThat(vault.rehydrate(r1)).isEqualTo(m1);
        assertThat(vault.rehydrate("Unknown [EMAIL_9] stays")).isEqualTo("Unknown [EMAIL_9] stays");
    }

    @Test
    void streamingRehydrationHandlesEverySplitPoint() {
        RedactionVault vault = new RedactionVault();
        String original = "Dear Priya Sharma, we emailed priya.sharma@acme.in [ref] about card 4111 1111 1111 1111.";
        String redacted = vault.redact(original, engine.scan(original).findings());
        assertThat(redacted).contains("[PERSON_1]", "[EMAIL_1]", "[CREDIT_CARD_1]");
        for (int a = 0; a <= redacted.length(); a++) {
            for (int b = a; b <= redacted.length(); b += 3) {
                StreamingRehydrator s = vault.streaming();
                String out = s.feed(redacted.substring(0, a)) + s.feed(redacted.substring(a, b))
                        + s.feed(redacted.substring(b)) + s.flush();
                assertThat(out).as("split at %d/%d", a, b).isEqualTo(original);
            }
        }
    }

    @Test
    void streamingWithRandomChunksAndCharacterByCharacter() {
        RedactionVault vault = new RedactionVault();
        String original = "Mail ravi@acme.in or call +91 98765 43210 [not a placeholder] [ALSO_NOT_ONE";
        String redacted = vault.redact(original, engine.scan(original).findings());
        Random r = new Random(1);
        for (int run = 0; run < 200; run++) {
            StreamingRehydrator s = vault.streaming();
            StringBuilder out = new StringBuilder();
            int i = 0;
            while (i < redacted.length()) {
                int n = 1 + r.nextInt(6);
                out.append(s.feed(redacted.substring(i, Math.min(redacted.length(), i + n))));
                i += n;
            }
            out.append(s.flush());
            assertThat(out.toString()).isEqualTo(original);
        }
    }

    @Test
    void textThatOnlyLooksLikeAPlaceholderPrefixIsReleasedOnceDisproved() {
        RedactionVault vault = new RedactionVault();
        vault.placeholderFor(EntityType.EMAIL, "a@b.co");
        StreamingRehydrator s = vault.streaming();
        assertThat(s.feed("see [EMA")).isEqualTo("see ");
        assertThat(s.feed("IL] ok")).isEqualTo("[EMAIL] ok");
        assertThat(s.feed(" [1, 2")).isEqualTo(" [1, 2");
    }
}
