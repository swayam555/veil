package dev.veil.detect;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Veil's built-in pattern detectors, each with the validation that keeps false positives down. */
public final class PatternLibrary {

    private PatternLibrary() {
    }

    // Digits that are not part of a longer number.
    private static final String NB = "(?<![\\d])";
    private static final String NA = "(?![\\d])";

    public static List<Detector> all() {
        return List.of(
                email(), indianPhone(), internationalPhone(), usPhone(),
                aadhaar(), pan(), gstin(), ifsc(), usSsn(), paymentCard(), iban(), bankAccount(), ipv4(),
                awsAccessKey(), awsSecretKey(), githubToken(), anthropicKey(), openAiKey(), slackToken(),
                stripeKey(), googleApiKey(), privateKey(), jwt(), genericSecret(), password(), connectionStringPassword());
    }

    static PatternDetector email() {
        return PatternDetector.builder("email", EntityType.EMAIL,
                        "(?<![\\w.+-])[A-Za-z0-9][A-Za-z0-9._%+-]{0,63}@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9-]{1,63})*\\.[A-Za-z]{2,24}\\b")
                .confidence(0.99).build();
    }

    static PatternDetector indianPhone() {
        return PatternDetector.builder("phone-in", EntityType.PHONE,
                        NB + "(?:(?:\\+|00)91[\\s-]?|0)?[6-9]\\d{4}[\\s-]?\\d{5}" + NA)
                .confidence(0.85).context(0.97, "phone", "mobile", "call", "whatsapp", "contact", "mob", "ph:", "tel")
                .detail("Indian mobile number").build();
    }

    static PatternDetector internationalPhone() {
        return PatternDetector.builder("phone-intl", EntityType.PHONE,
                        "(?<![\\w+])\\+(?!91)[1-9]\\d{0,2}[\\s.-]?\\(?\\d{1,4}\\)?(?:[\\s.-]?\\d{2,4}){2,4}" + NA)
                .validate(v -> {
                    int n = Checksums.digitsOnly(v).length();
                    return n >= 8 && n <= 15;
                })
                .confidence(0.85).context(0.95, "phone", "mobile", "call", "whatsapp", "contact", "tel")
                .detail("International phone number").build();
    }

    static PatternDetector usPhone() {
        return PatternDetector.builder("phone-us", EntityType.PHONE,
                        "(?<![\\w+])(?:\\(\\d{3}\\)\\s?|\\d{3}[.-])\\d{3}[.-]\\d{4}" + NA)
                .validate(v -> !Checksums.digitsOnly(v).startsWith("0") && !Checksums.digitsOnly(v).startsWith("1"))
                .confidence(0.75).context(0.95, "phone", "call", "cell", "mobile", "contact", "tel", "fax")
                .detail("US phone number").build();
    }

    static PatternDetector aadhaar() {
        // Aadhaar numbers never start with 0 or 1, and the last digit is a Verhoeff check digit.
        return PatternDetector.builder("aadhaar", EntityType.AADHAAR,
                        NB + "[2-9]\\d{3}(?:[\\s-]?)\\d{4}(?:[\\s-]?)\\d{4}" + NA)
                .validate(v -> Checksums.digitsOnly(v).length() == 12 && Checksums.verhoeff(v)
                        && !allSameDigit(Checksums.digitsOnly(v)))
                .confidence(0.8).context(0.99, "aadhaar", "aadhar", "adhaar", "uid", "uidai", "आधार")
                .build();
    }

    static PatternDetector pan() {
        // Fourth character encodes the holder type: P person, C company, H HUF, F firm, A AOP, T trust, ...
        return PatternDetector.builder("pan", EntityType.PAN, "\\b[A-Z]{3}[ABCFGHLJPT][A-Z]\\d{4}[A-Z]\\b")
                .confidence(0.9).context(0.99, "pan", "permanent account", "income tax", "itr")
                .build();
    }

    private static final Pattern GSTIN_STATE = Pattern.compile("(0[1-9]|[12]\\d|3[0-8]|97|99)");

    static PatternDetector gstin() {
        return PatternDetector.builder("gstin", EntityType.GSTIN,
                        "\\b\\d{2}[A-Z]{5}\\d{4}[A-Z][1-9A-Z]Z[0-9A-Z]\\b")
                .validate(v -> GSTIN_STATE.matcher(v.substring(0, 2)).matches() && Checksums.gstin(v))
                .confidence(0.97).build();
    }

    static PatternDetector ifsc() {
        return PatternDetector.builder("ifsc", EntityType.IFSC, "\\b[A-Z]{4}0[A-Z0-9]{6}\\b")
                .confidence(0.75).context(0.97, "ifsc", "branch", "bank", "neft", "rtgs", "imps")
                .build();
    }

    static PatternDetector usSsn() {
        return PatternDetector.builder("ssn", EntityType.US_SSN, NB + "\\d{3}-\\d{2}-\\d{4}" + NA)
                .validate(Checksums::ssn)
                .confidence(0.85).context(0.99, "ssn", "social security", "social sec", "taxpayer")
                .build();
    }

    static PatternDetector paymentCard() {
        return PatternDetector.builder("card", EntityType.CREDIT_CARD, NB + "\\d(?:[ -]?\\d){12,18}" + NA)
                .validate(v -> {
                    String d = Checksums.digitsOnly(v);
                    return d.length() >= 13 && d.length() <= 19 && Checksums.luhn(d) && knownCardPrefix(d)
                            && !allSameDigit(d) && consistentSeparators(v);
                })
                .confidence(0.9).context(0.99, "card", "visa", "mastercard", "amex", "rupay", "credit", "debit", "cvv", "expiry")
                .penalty(0.55, "account", "a/c", "acct", "tracking", "order", "awb", "invoice", "ref")
                .build();
    }

    static PatternDetector iban() {
        return PatternDetector.builder("iban", EntityType.IBAN,
                        "\\b[A-Z]{2}\\d{2}(?: ?[A-Z0-9]{4}){2,7}(?: ?[A-Z0-9]{1,4})?\\b")
                .validate(Checksums::iban)
                .confidence(0.97).build();
    }

    static PatternDetector bankAccount() {
        // Too ambiguous without context: only fires next to words like "account number".
        return PatternDetector.builder("bank-account", EntityType.BANK_ACCOUNT, NB + "\\d{9,18}" + NA)
                .validate(v -> !allSameDigit(v))
                .confidence(0.85).context(0.9, "account no", "account number", "a/c", "acct", "acc no", "account:", "account #", "bank account", "savings account", "current account")
                .requireContext().build();
    }

    static PatternDetector ipv4() {
        return PatternDetector.builder("ipv4", EntityType.IP_ADDRESS,
                        "(?<![\\w.])(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(?![\\w.])")
                .validate(v -> !v.equals("0.0.0.0") && !v.equals("127.0.0.1") && !v.startsWith("255."))
                .confidence(0.8).context(0.95, "ip", "host", "server", "ssh", "address", "vpn", "connect")
                .build();
    }

    // ---- Secrets ----

    static PatternDetector awsAccessKey() {
        return PatternDetector.builder("aws-access-key", EntityType.API_KEY, "\\b(?:AKIA|ASIA|AGPA|AIDA|AROA)[0-9A-Z]{16}\\b")
                .confidence(0.99).detail("AWS access key ID").build();
    }

    static PatternDetector awsSecretKey() {
        return PatternDetector.builder("aws-secret-key", EntityType.API_KEY,
                        "(?i)aws.{0,24}?(?:secret|private)?.{0,8}?[=:]\\s*[\"']?([A-Za-z0-9/+=]{40})(?![A-Za-z0-9/+=])")
                .group(1).validate(v -> Checksums.entropy(v) > 3.8)
                .confidence(0.95).detail("AWS secret access key").build();
    }

    static PatternDetector githubToken() {
        return PatternDetector.builder("github-token", EntityType.API_KEY,
                        "\\b(?:gh[pousr]_[A-Za-z0-9]{36,255}|github_pat_[A-Za-z0-9_]{50,255})\\b")
                .confidence(0.99).detail("GitHub token").build();
    }

    static PatternDetector anthropicKey() {
        return PatternDetector.builder("anthropic-key", EntityType.API_KEY, "\\bsk-ant-[A-Za-z0-9_-]{20,}")
                .confidence(0.99).detail("Anthropic API key").build();
    }

    static PatternDetector openAiKey() {
        return PatternDetector.builder("openai-key", EntityType.API_KEY, "\\bsk-(?!ant-)(?:proj-|svcacct-|admin-)?[A-Za-z0-9_-]{20,}")
                .validate(v -> Checksums.entropy(v) > 3.5)
                .confidence(0.97).detail("OpenAI API key").build();
    }

    static PatternDetector slackToken() {
        return PatternDetector.builder("slack-token", EntityType.API_KEY, "\\bxox[baprse]-[A-Za-z0-9-]{10,}")
                .confidence(0.98).detail("Slack token").build();
    }

    static PatternDetector stripeKey() {
        return PatternDetector.builder("stripe-key", EntityType.API_KEY, "\\b(?:sk|rk|pk)_(?:live|test)_[A-Za-z0-9]{16,}\\b")
                .confidence(0.98).detail("Stripe key").build();
    }

    static PatternDetector googleApiKey() {
        return PatternDetector.builder("google-api-key", EntityType.API_KEY, "\\bAIza[0-9A-Za-z_-]{35}(?![0-9A-Za-z_-])")
                .confidence(0.98).detail("Google API key").build();
    }

    static PatternDetector privateKey() {
        return PatternDetector.builder("private-key", EntityType.PRIVATE_KEY,
                        "-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED |PGP )?PRIVATE KEY(?: BLOCK)?-----[\\s\\S]*?(?:-----END (?:RSA |EC |DSA |OPENSSH |ENCRYPTED |PGP )?PRIVATE KEY(?: BLOCK)?-----|\\z)")
                .confidence(0.99).build();
    }

    static PatternDetector jwt() {
        return PatternDetector.builder("jwt", EntityType.JWT, "\\beyJ[A-Za-z0-9_-]{8,}\\.eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}")
                .confidence(0.97).build();
    }

    private static final Set<String> NOT_SECRETS = Set.of("true", "false", "null", "none", "undefined", "changeme", "your_api_key_here", "xxxxxxxxxxxxxxxx");

    static PatternDetector genericSecret() {
        return PatternDetector.builder("generic-secret", EntityType.API_KEY,
                        "(?i)\\b(?:api[_-]?key|apikey|secret(?:[_-]?key)?|access[_-]?token|auth[_-]?token|client[_-]?secret|bearer)[\"']?\\s*(?:[:=]|is|=>)?\\s*[\"']?([A-Za-z0-9_\\-/+=.]{16,200})")
                .group(1)
                .validate(v -> !NOT_SECRETS.contains(v.toLowerCase()) && !v.startsWith("eyJ") && Checksums.entropy(v) >= 3.5
                        && v.chars().anyMatch(Character::isDigit) && v.chars().anyMatch(Character::isLetter))
                .confidence(0.85).detail("Credential value").build();
    }

    static PatternDetector password() {
        return PatternDetector.builder("password", EntityType.PASSWORD,
                        "(?i)\\b(?:password|passwd|pwd|passcode|pass)\\s*(?:is|=|:|-)\\s*[\"']?([^\\s\"']{4,64})")
                .group(1)
                .validate(v -> !NOT_SECRETS.contains(v.toLowerCase()) && !v.equalsIgnoreCase("required")
                        && !v.equalsIgnoreCase("incorrect") && !v.equalsIgnoreCase("expired"))
                .confidence(0.9).build();
    }

    static PatternDetector connectionStringPassword() {
        return PatternDetector.builder("connection-string", EntityType.PASSWORD,
                        "(?i)\\b(?:postgres(?:ql)?|mysql|mariadb|mongodb(?:\\+srv)?|redis|rediss|amqps?|mssql|sqlserver)://[^:/\\s@]+:([^@\\s]{3,})@")
                .group(1).confidence(0.97).detail("Password in a connection string").build();
    }

    // ---- helpers ----

    static boolean knownCardPrefix(String d) {
        return d.startsWith("4")                                                  // Visa
                || d.matches("^(5[1-5]|2[2-7]).*")                                // Mastercard
                || d.matches("^3[47].*")                                          // Amex
                || d.matches("^(6011|65|64[4-9]).*")                              // Discover
                || d.matches("^(60|652|81|82|508|353|356).*")                     // RuPay
                || d.matches("^35(2[89]|[3-8]).*")                                // JCB
                || d.matches("^(30[0-5]|36|38|39).*")                             // Diners
                || d.matches("^62.*");                                            // UnionPay
    }

    static boolean allSameDigit(String d) {
        String digits = Checksums.digitsOnly(d);
        return !digits.isEmpty() && digits.chars().allMatch(c -> c == digits.charAt(0));
    }

    /** "4111 1111 1111 1111" or "4111-1111-..." but not "4111 1111-1111 1111" (usually two numbers). */
    private static boolean consistentSeparators(String v) {
        return !(v.contains(" ") && v.contains("-"));
    }
}
