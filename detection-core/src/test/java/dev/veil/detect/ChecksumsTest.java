package dev.veil.detect;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class ChecksumsTest {

    @Test
    void luhnAcceptsKnownTestCardsAndRejectsTypos() {
        assertThat(Checksums.luhn("4111 1111 1111 1111")).isTrue();
        assertThat(Checksums.luhn("5555-5555-5555-4444")).isTrue();
        assertThat(Checksums.luhn("378282246310005")).isTrue();
        assertThat(Checksums.luhn("4111111111111112")).isFalse();
        assertThat(Checksums.luhn("")).isFalse();
    }

    @Test
    void verhoeffCheckDigitRoundTripsAndCatchesEverySingleDigitError() {
        Random r = new Random(7);
        for (int n = 0; n < 500; n++) {
            StringBuilder base = new StringBuilder().append(2 + r.nextInt(8));
            for (int i = 0; i < 10; i++) base.append(r.nextInt(10));
            String full = base.toString() + Checksums.verhoeffCheckDigit(base.toString());
            assertThat(Checksums.verhoeff(full)).as(full).isTrue();
            // Any single changed digit must fail.
            int pos = r.nextInt(12);
            char changed = (char) ('0' + (full.charAt(pos) - '0' + 1 + r.nextInt(9)) % 10);
            String typo = full.substring(0, pos) + changed + full.substring(pos + 1);
            assertThat(Checksums.verhoeff(typo)).as(typo).isFalse();
        }
    }

    @Test
    void verhoeffCatchesAdjacentTranspositions() {
        String base = "23456789012";
        String full = base + Checksums.verhoeffCheckDigit(base);
        for (int i = 0; i + 1 < full.length(); i++) {
            if (full.charAt(i) == full.charAt(i + 1)) continue;
            String swapped = full.substring(0, i) + full.charAt(i + 1) + full.charAt(i) + full.substring(i + 2);
            assertThat(Checksums.verhoeff(swapped)).as(swapped).isFalse();
        }
        assertThat(Checksums.verhoeff(full)).isTrue();
    }

    @Test
    void ibanUsesMod97() {
        assertThat(Checksums.iban("GB82 WEST 1234 5698 7654 32")).isTrue();
        assertThat(Checksums.iban("DE89370400440532013000")).isTrue();
        assertThat(Checksums.iban("GB82 WEST 1234 5698 7654 33")).isFalse();
        assertThat(Checksums.iban("GB82")).isFalse();
    }

    @Test
    void gstinCheckCharacter() {
        assertThat(Checksums.gstin("27AAPFU0939F1ZV")).isTrue();
        assertThat(Checksums.gstin("27AAPFU0939F1ZW")).isFalse();
        String first14 = "29ABCDE1234F1Z";
        assertThat(Checksums.gstin(first14 + Checksums.gstinCheckChar(first14))).isTrue();
    }

    @Test
    void ssnStructuralRules() {
        assertThat(Checksums.ssn("536-22-1234")).isTrue();
        assertThat(Checksums.ssn("000-22-1234")).isFalse();
        assertThat(Checksums.ssn("666-22-1234")).isFalse();
        assertThat(Checksums.ssn("912-22-1234")).isFalse();
        assertThat(Checksums.ssn("536-00-1234")).isFalse();
        assertThat(Checksums.ssn("536-22-0000")).isFalse();
        assertThat(Checksums.ssn("123-45-6789")).isFalse();
    }

    @Test
    void entropySeparatesKeysFromWords() {
        assertThat(Checksums.entropy("aaaaaaaa")).isZero();
        assertThat(Checksums.entropy("password")).isLessThan(3.0);
        assertThat(Checksums.entropy("q8ZtR2vN7xLk4PmW9sYb")).isGreaterThan(4.0);
    }
}
