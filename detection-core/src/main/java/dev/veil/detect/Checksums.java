package dev.veil.detect;

import java.math.BigInteger;

/**
 * Check-digit algorithms. They are what separates "a 12-digit number" from "an Aadhaar number": a random
 * 12-digit number passes Verhoeff only one time in ten, and a random 16-digit number passes Luhn one time in ten.
 */
public final class Checksums {

    private Checksums() {
    }

    /** Luhn (mod 10), used by payment cards. Non-digits are ignored. */
    public static boolean luhn(String number) {
        String digits = digitsOnly(number);
        if (digits.isEmpty()) return false;
        int sum = 0;
        boolean dbl = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }

    private static final int[][] VERHOEFF_D = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, {1, 2, 3, 4, 0, 6, 7, 8, 9, 5}, {2, 3, 4, 0, 1, 7, 8, 9, 5, 6},
            {3, 4, 0, 1, 2, 8, 9, 5, 6, 7}, {4, 0, 1, 2, 3, 9, 5, 6, 7, 8}, {5, 9, 8, 7, 6, 0, 4, 3, 2, 1},
            {6, 5, 9, 8, 7, 1, 0, 4, 3, 2}, {7, 6, 5, 9, 8, 2, 1, 0, 4, 3}, {8, 7, 6, 5, 9, 3, 2, 1, 0, 4},
            {9, 8, 7, 6, 5, 4, 3, 2, 1, 0}};
    private static final int[][] VERHOEFF_P = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, {1, 5, 7, 6, 2, 8, 3, 0, 9, 4}, {5, 8, 0, 3, 7, 9, 6, 1, 4, 2},
            {8, 9, 1, 6, 0, 4, 3, 5, 2, 7}, {9, 4, 5, 3, 1, 2, 6, 8, 7, 0}, {4, 2, 8, 6, 5, 7, 3, 9, 0, 1},
            {2, 7, 9, 3, 8, 0, 6, 4, 1, 5}, {7, 0, 4, 6, 9, 1, 3, 2, 5, 8}};
    private static final int[] VERHOEFF_INV = {0, 4, 3, 2, 1, 5, 6, 7, 8, 9};

    /** Verhoeff, used by Aadhaar. Catches every single-digit error and every adjacent transposition. */
    public static boolean verhoeff(String number) {
        String digits = digitsOnly(number);
        if (digits.isEmpty()) return false;
        int c = 0;
        for (int i = 0; i < digits.length(); i++) {
            int d = digits.charAt(digits.length() - 1 - i) - '0';
            c = VERHOEFF_D[c][VERHOEFF_P[i % 8][d]];
        }
        return c == 0;
    }

    /** The Verhoeff check digit to append to {@code number}. Used to generate test data. */
    public static int verhoeffCheckDigit(String number) {
        String digits = digitsOnly(number);
        int c = 0;
        for (int i = 0; i < digits.length(); i++) {
            int d = digits.charAt(digits.length() - 1 - i) - '0';
            c = VERHOEFF_D[c][VERHOEFF_P[(i + 1) % 8][d]];
        }
        return VERHOEFF_INV[c];
    }

    /** ISO 13616 IBAN check: move the first four characters to the end, letters to numbers, mod 97 == 1. */
    public static boolean iban(String value) {
        String s = value.replace(" ", "").toUpperCase();
        if (s.length() < 15 || s.length() > 34) return false;
        String rearranged = s.substring(4) + s.substring(0, 4);
        StringBuilder numeric = new StringBuilder(rearranged.length() * 2);
        for (char ch : rearranged.toCharArray()) {
            if (Character.isDigit(ch)) numeric.append(ch);
            else if (ch >= 'A' && ch <= 'Z') numeric.append(ch - 'A' + 10);
            else return false;
        }
        return new BigInteger(numeric.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
    }

    private static final String GSTIN_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /** GSTIN's 15th character is a base-36 check character over the first 14. */
    public static boolean gstin(String value) {
        String s = value.toUpperCase();
        if (s.length() != 15) return false;
        return gstinCheckChar(s.substring(0, 14)) == s.charAt(14);
    }

    public static char gstinCheckChar(String first14) {
        int sum = 0;
        for (int i = 0; i < 14; i++) {
            int v = GSTIN_CHARS.indexOf(first14.charAt(i));
            if (v < 0) return '?';
            int product = v * (i % 2 == 0 ? 1 : 2);
            sum += product / 36 + product % 36;
        }
        return GSTIN_CHARS.charAt((36 - sum % 36) % 36);
    }

    /** US SSN structural rules: area not 000, 666 or 900-999; group not 00; serial not 0000. */
    public static boolean ssn(String value) {
        String d = digitsOnly(value);
        if (d.length() != 9) return false;
        int area = Integer.parseInt(d.substring(0, 3));
        int group = Integer.parseInt(d.substring(3, 5));
        int serial = Integer.parseInt(d.substring(5));
        return area != 0 && area != 666 && area < 900 && group != 0 && serial != 0
                && !d.equals("123456789") && !d.equals("078051120");
    }

    /** Shannon entropy in bits per character. Random API keys sit around 4.5-6; English words around 2.5-3.5. */
    public static double entropy(String s) {
        if (s.isEmpty()) return 0;
        int[] counts = new int[128];
        int other = 0;
        for (char ch : s.toCharArray()) {
            if (ch < 128) counts[ch]++;
            else other++;
        }
        double h = 0;
        for (int c : counts) h += term(c, s.length());
        return h + term(other, s.length());
    }

    private static double term(int count, int n) {
        if (count == 0) return 0;
        double p = (double) count / n;
        return -p * (Math.log(p) / Math.log(2));
    }

    public static String digitsOnly(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') b.append(ch);
        }
        return b.toString();
    }
}
