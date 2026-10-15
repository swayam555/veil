package dev.veil.control.sim;

import dev.veil.detect.Checksums;

import java.util.List;
import java.util.random.RandomGenerator;

/** Realistic-looking but fake sensitive values with valid check digits, for the traffic simulator. */
final class SyntheticData {

    private static final List<String> FIRST = List.of("Priya", "Rahul", "Ananya", "Vikram", "Sneha", "Arjun", "Kavita",
            "Rohan", "Meera", "Sanjay", "Deepika", "Karan", "Neha", "Aditya", "Pooja", "Michael", "Jennifer", "David", "Sarah");
    private static final List<String> LAST = List.of("Sharma", "Verma", "Iyer", "Reddy", "Nair", "Gupta", "Patel", "Mehta",
            "Kulkarni", "Banerjee", "Singh", "Johnson", "Williams", "Brown");
    private static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private final RandomGenerator rnd;

    SyntheticData(RandomGenerator rnd) {
        this.rnd = rnd;
    }

    String person() {
        return pick(FIRST) + " " + pick(LAST);
    }

    String email(String person) {
        String[] p = person.toLowerCase().split(" ");
        return p[0] + "." + p[1] + "@" + pick(List.of("gmail.com", "outlook.com", "yahoo.co.in", "acme-customers.in"));
    }

    String phone() {
        return "+91 " + (6 + rnd.nextInt(4)) + digits(4) + " " + digits(5);
    }

    String aadhaar() {
        String base = (2 + rnd.nextInt(8)) + digits(10);
        String n = base + Checksums.verhoeffCheckDigit(base);
        return n.substring(0, 4) + " " + n.substring(4, 8) + " " + n.substring(8);
    }

    String pan() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 3; i++) b.append(UPPER.charAt(rnd.nextInt(26)));
        b.append('P').append(UPPER.charAt(rnd.nextInt(26))).append(digits(4)).append(UPPER.charAt(rnd.nextInt(26)));
        return b.toString();
    }

    String card() {
        String body = "4" + digits(14);
        for (int d = 0; d < 10; d++) {
            if (Checksums.luhn(body + d)) {
                String n = body + d;
                return n.substring(0, 4) + " " + n.substring(4, 8) + " " + n.substring(8, 12) + " " + n.substring(12);
            }
        }
        throw new IllegalStateException();
    }

    String awsKey() {
        StringBuilder b = new StringBuilder("AKIA");
        for (int i = 0; i < 16; i++) b.append("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".charAt(rnd.nextInt(32)));
        return b.toString();
    }

    String openAiKey() {
        StringBuilder b = new StringBuilder("sk-proj-");
        for (int i = 0; i < 40; i++) b.append(ALNUM.charAt(rnd.nextInt(ALNUM.length())));
        return b.toString();
    }

    String password() {
        return pick(List.of("Summer@2026", "Welcome#123", "Acme!Prod2026", "Kavya@1993", "Tr0ub4dor&3"));
    }

    String amountCrore() {
        return String.valueOf(100 + rnd.nextInt(900));
    }

    String digits(int n) {
        StringBuilder b = new StringBuilder(n);
        for (int i = 0; i < n; i++) b.append(rnd.nextInt(10));
        return b.toString();
    }

    <T> T pick(List<T> list) {
        return list.get(rnd.nextInt(list.size()));
    }
}
