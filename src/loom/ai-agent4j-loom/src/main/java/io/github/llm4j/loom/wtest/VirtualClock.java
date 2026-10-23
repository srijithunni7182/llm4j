package io.github.llm4j.loom.wtest;

import io.github.llm4j.ratelimit.Sleeper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The time of a test case. A {@code sleep}, a retry's backoff and a rate-limit wait move this clock
 * forward at once and wait for nothing, so a case that sleeps for a day takes a millisecond and can
 * say exactly how long it waited.
 */
final class VirtualClock extends Clock implements Sleeper {

    static final Instant DEFAULT_START = Instant.parse("2026-01-01T00:00:00Z");

    private final Instant start;
    private final AtomicLong elapsedMillis = new AtomicLong();

    VirtualClock(Instant start) {
        this.start = start == null ? DEFAULT_START : start;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return start.plusMillis(elapsedMillis.get());
    }

    @Override
    public void sleep(Duration duration) {
        if (!duration.isNegative()) elapsedMillis.addAndGet(duration.toMillis());
    }

    /** How long the case has waited, in virtual time. */
    Duration elapsed() {
        return Duration.ofMillis(elapsedMillis.get());
    }
}
