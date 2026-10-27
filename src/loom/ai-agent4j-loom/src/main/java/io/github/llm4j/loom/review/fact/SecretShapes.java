package io.github.llm4j.loom.review.fact;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Shapes of keys that must not be written in a script, a prompt or a transcript; a plain pattern
 * list, no entropy guess.
 */
public final class SecretShapes {

    private SecretShapes() {}

    private static final List<Pattern> SECRETS =
            List.of(
                    Pattern.compile("\\bsk-[A-Za-z0-9_-]{20,}"),
                    Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
                    Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{30,}"),
                    Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}"),
                    Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}\\b"),
                    Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
                    Pattern.compile(
                            "(?i)\\b(api[_-]?key|secret|token|password)\\b\\s*[:=]\\s*[\"']?[A-Za-z0-9/+_-]{16,}"));

    /**
     * More shapes that a captured run must never keep: they are looser (a bearer token in a log, a
     * password in a URL) than the review's rule for scripts and are used only by {@link #scrub}.
     */
    private static final List<Pattern> IN_LOGS =
            List.of(
                    Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{12,}"),
                    Pattern.compile("(?im)^\\s*Authorization\\s*:.*$"),
                    Pattern.compile(
                            "\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"),
                    Pattern.compile("(?i)\\b[a-z][a-z0-9+.-]*://[^\\s/:@]+:[^\\s/@]+@\\S+"),
                    Pattern.compile("\\b[sr]k_(?:live|test)_[A-Za-z0-9]{16,}"),
                    Pattern.compile("\\bAIza[0-9A-Za-z_-]{30,}"),
                    Pattern.compile(
                            "(?i)\\b(?:password|passwd|pwd|secret|token|api[_-]?key)\\b\\s*[:=]\\s*[\"']?[^\\s\"',;]{6,}"),
                    Pattern.compile("\\bya29\\.[A-Za-z0-9_-]{20,}"),
                    Pattern.compile("\\bnpm_[A-Za-z0-9]{30,}"),
                    Pattern.compile("https://hooks\\.slack\\.com/services/[A-Za-z0-9/]+"),
                    Pattern.compile(
                            "-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)"));

    /**
     * The text with everything shaped like a key, token or password replaced by {@code ‹secret›}.
     */
    public static String scrub(String text) {
        if (text == null) return null;
        String out = text;
        for (Pattern p : IN_LOGS) out = p.matcher(out).replaceAll("‹secret›");
        for (Pattern p : SECRETS) out = p.matcher(out).replaceAll("‹secret›");
        return out;
    }

    /** Whether the text holds something shaped like a key or a password. */
    public static boolean in(String text) {
        return text != null && SECRETS.stream().anyMatch(p -> p.matcher(text).find());
    }
}
