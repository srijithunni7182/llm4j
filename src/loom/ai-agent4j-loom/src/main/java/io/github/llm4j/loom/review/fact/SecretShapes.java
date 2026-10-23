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

    /** Whether the text holds something shaped like a key or a password. */
    public static boolean in(String text) {
        return text != null && SECRETS.stream().anyMatch(p -> p.matcher(text).find());
    }
}
