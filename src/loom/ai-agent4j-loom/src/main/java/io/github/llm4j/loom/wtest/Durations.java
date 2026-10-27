package io.github.llm4j.loom.wtest;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Durations as a test file writes them: {@code 500ms}, {@code 30s}, {@code 5m}, {@code 2h}, {@code
 * 1d}.
 */
final class Durations {

    private static final Pattern P = Pattern.compile("(\\d+)\\s*(ms|s|m|h|d)");

    private Durations() {}

    /** The duration, or null when the text is not one. */
    static Duration parse(String text) {
        Matcher m = P.matcher(text.strip());
        if (!m.matches()) return null;
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "ms" -> Duration.ofMillis(n);
            case "s" -> Duration.ofSeconds(n);
            case "m" -> Duration.ofMinutes(n);
            case "h" -> Duration.ofHours(n);
            default -> Duration.ofDays(n);
        };
    }

    static String show(Duration d) {
        long ms = d.toMillis();
        if (ms % 86_400_000 == 0 && ms > 0) return ms / 86_400_000 + "d";
        if (ms % 3_600_000 == 0 && ms > 0) return ms / 3_600_000 + "h";
        if (ms % 60_000 == 0 && ms > 0) return ms / 60_000 + "m";
        if (ms % 1000 == 0) return ms / 1000 + "s";
        return ms + "ms";
    }
}
