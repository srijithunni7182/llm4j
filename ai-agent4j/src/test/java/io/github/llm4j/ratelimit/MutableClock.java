package io.github.llm4j.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock tests move by hand. Thread-safe. */
public final class MutableClock extends Clock {

    public static final Instant T0 = Instant.parse("2026-09-27T10:00:00Z");

    private volatile Instant now;
    private final ZoneId zone;

    public MutableClock() {
        this(T0, ZoneOffset.UTC);
    }

    public MutableClock(Instant now, ZoneId zone) {
        this.now = now;
        this.zone = zone;
    }

    public void set(Instant instant) {
        now = instant;
    }

    public void set(String iso) {
        now = Instant.parse(iso);
    }

    public void advance(Duration d) {
        now = now.plus(d);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(now, zone);
    }

    @Override
    public Instant instant() {
        return now;
    }
}
