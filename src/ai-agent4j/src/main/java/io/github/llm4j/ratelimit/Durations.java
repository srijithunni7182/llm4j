package io.github.llm4j.ratelimit;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses provider duration strings such as {@code 6m0s}, {@code 1h2m3.5s}, {@code 120ms}, {@code 34s}, {@code 0.5s}. */
public final class Durations {

    private static final Pattern PART = Pattern.compile("(\\d+(?:\\.\\d+)?)(ms|h|m|s)");

    private Durations() { }

    /** The duration, or null if the text isn't one. */
    public static Duration parse(String text) {
        if (text == null) return null;
        String s = text.trim();
        if (s.isEmpty()) return null;
        Matcher m = PART.matcher(s);
        int pos = 0;
        double millis = 0;
        boolean any = false;
        while (m.find()) {
            if (m.start() != pos) return null;
            double n = Double.parseDouble(m.group(1));
            millis += switch (m.group(2)) {
                case "h" -> n * 3_600_000;
                case "m" -> n * 60_000;
                case "s" -> n * 1000;
                default -> n; // ms
            };
            pos = m.end();
            any = true;
        }
        if (!any || pos != s.length()) return null;
        return Duration.ofNanos(Math.round(millis * 1_000_000));
    }
}
