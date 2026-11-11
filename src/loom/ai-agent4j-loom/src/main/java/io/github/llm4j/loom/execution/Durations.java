package io.github.llm4j.loom.execution;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The durations a script writes in settings ({@code 90s}, {@code 30m}, {@code 2h}, {@code 500ms}). */
final class Durations {

    private static final Pattern DURATION = Pattern.compile("(\\d+(?:\\.\\d+)?)(ms|s|m|h)");

    private Durations() {}

    /** The duration, or the fallback when the text is not one ({@code weave check} reports that). */
    static Duration parse(String text, Duration fallback) {
        if (text == null) return fallback;
        Matcher m = DURATION.matcher(text.strip());
        if (!m.matches()) return fallback;
        double value = Double.parseDouble(m.group(1));
        return Duration.ofMillis(
                (long)
                        switch (m.group(2)) {
                            case "ms" -> value;
                            case "m" -> value * 60_000;
                            case "h" -> value * 3_600_000;
                            default -> value * 1_000;
                        });
    }
}
