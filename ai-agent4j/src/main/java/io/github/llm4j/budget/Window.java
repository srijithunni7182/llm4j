package io.github.llm4j.budget;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/** A period after which a budget's spend starts again from zero, aligned to the clock's time zone. */
public enum Window {
    MINUTE(ChronoUnit.MINUTES),
    HOUR(ChronoUnit.HOURS),
    DAY(ChronoUnit.DAYS);

    private final ChronoUnit unit;

    Window(ChronoUnit unit) {
        this.unit = unit;
    }

    /** The start of the window containing {@code now}. */
    public Instant start(Instant now, ZoneId zone) {
        return ZonedDateTime.ofInstant(now, zone).truncatedTo(unit).toInstant();
    }

    /** The end (exclusive) of the window containing {@code now}: when the budget refills. */
    public Instant end(Instant now, ZoneId zone) {
        return ZonedDateTime.ofInstant(now, zone).truncatedTo(unit).plus(1, unit).toInstant();
    }

    /** Parses {@code minute}, {@code hour} or {@code day} (any case). */
    public static Window parse(String text) {
        return valueOf(text.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
