package io.github.llm4j.loom.resume;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock tests move by hand; starts at 2026-09-27T10:00:00Z. */
public final class TestClock extends Clock {

    public static final Instant T0 = Instant.parse("2026-09-27T10:00:00Z");

    private volatile Instant now = T0;

    public void set(String iso) {
        now = Instant.parse(iso);
    }

    public void set(Instant instant) {
        now = instant;
    }

    public void advance(Duration d) {
        now = now.plus(d);
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
        return now;
    }
}
