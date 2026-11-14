package io.github.llm4j.tools;

import io.github.llm4j.privacy.PIIEntity;
import io.github.llm4j.privacy.RegexPIIDetector;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rules {@code scan} applies (D14), versioned so a finding can be reproduced: known shapes of
 * credentials, long random-looking strings, and personal data through the core {@link
 * RegexPIIDetector}. A finding is a rule and a character range, never the text it matched. Every
 * pattern has bounded repetition, and the text it runs over is bounded by the caller.
 */
final class ScanRules {

    /** Changes whenever a rule is added, removed or changed. */
    static final String VERSION = "scan-1";

    /**
     * Shortest string the entropy rule looks at, so ordinary words and short ids are not flagged.
     */
    static final int ENTROPY_MIN_LENGTH = 32;

    /** Bits per character above which a long string looks random; hexadecimal tops out at 4. */
    static final double ENTROPY_THRESHOLD = 4.5;

    /** A place in the text a rule matched: the rule and a half-open character range. */
    record Hit(String rule, int start, int end) {}

    private record Shape(String rule, Pattern pattern) {}

    private static final List<Shape> SECRETS =
            List.of(
                    new Shape(
                            "private_key",
                            Pattern.compile("-----BEGIN [A-Z ]{0,40}PRIVATE KEY-----")),
                    new Shape("aws_access_key", Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b")),
                    new Shape(
                            "github_token", Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{36,255}\\b")),
                    new Shape("slack_token", Pattern.compile("\\bxox[abprs]-[A-Za-z0-9-]{10,255}")),
                    new Shape("google_api_key", Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}\\b")),
                    new Shape("api_key_sk", Pattern.compile("\\bsk-[A-Za-z0-9_-]{20,255}")),
                    new Shape(
                            "jwt",
                            Pattern.compile(
                                    "\\beyJ[A-Za-z0-9_-]{10,2000}\\.eyJ[A-Za-z0-9_-]{10,2000}\\.[A-Za-z0-9_-]{10,2000}")),
                    new Shape(
                            "assigned_secret",
                            Pattern.compile(
                                    "(?i)\\b(?:password|passwd|secret|api[_-]?key|access[_-]?token|token)"
                                            + "\\s{0,5}[:=]\\s{0,5}['\"]?[^\\s'\"]{8,255}")));

    private static final Pattern WORDS =
            Pattern.compile("[A-Za-z0-9+/=_-]{" + ENTROPY_MIN_LENGTH + ",4096}");

    /** The personal-data kinds {@code pii:} may name. */
    static final Set<String> PII_KINDS =
            Set.of("email", "phone", "ssn", "credit_card", "ip_address", "url");

    /** Asked for when {@code pii:} is not given: an address or a URL alone is rarely personal. */
    static final Set<String> DEFAULT_PII = Set.of("email", "phone", "ssn", "credit_card");

    private ScanRules() {}

    /** Every secret and personal-data hit in {@code text}, by position, without overlaps. */
    static List<Hit> scan(String text, boolean secrets, Set<String> pii) {
        List<Hit> hits = new ArrayList<>();
        if (secrets) {
            for (Shape s : SECRETS) {
                Matcher m = s.pattern().matcher(text);
                while (m.find()) add(hits, new Hit(s.rule(), m.start(), m.end()));
            }
            Matcher m = WORDS.matcher(text);
            while (m.find())
                if (random(m.group())) add(hits, new Hit("high_entropy", m.start(), m.end()));
        }
        if (!pii.isEmpty()) {
            for (PIIEntity e : new RegexPIIDetector().detect(text).getEntities()) {
                String kind = e.getType().name().toLowerCase(Locale.ROOT);
                if (pii.contains(kind))
                    add(hits, new Hit("pii_" + kind, e.getStartIndex(), e.getEndIndex()));
            }
        }
        hits.sort(Comparator.comparingInt(Hit::start));
        return hits;
    }

    /** Keeps the first rule to claim a range; a later one that overlaps it is the same finding. */
    private static void add(List<Hit> hits, Hit hit) {
        for (Hit h : hits) if (hit.start() < h.end() && h.start() < hit.end()) return;
        hits.add(hit);
    }

    /** Shannon entropy over a long, mixed string: letters and digits, not only hexadecimal. */
    static boolean random(String s) {
        boolean upper = false, lower = false, digit = false;
        int[] counts = new int[128]; // the pattern that finds candidates matches ASCII only
        for (char c : s.toCharArray()) {
            counts[c]++;
            upper |= Character.isUpperCase(c);
            lower |= Character.isLowerCase(c);
            digit |= Character.isDigit(c);
        }
        if (!(upper && lower && digit)) return false;
        double bits = 0;
        for (int n : counts) {
            if (n == 0) continue;
            double p = (double) n / s.length();
            bits -= p * (Math.log(p) / Math.log(2));
        }
        return bits >= ENTROPY_THRESHOLD;
    }
}
