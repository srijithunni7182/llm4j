package io.github.llm4j.spring;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock the test moves by hand. */
final class MutableClock extends Clock {

    private volatile Instant now;

    MutableClock(String iso) {
        this.now = Instant.parse(iso);
    }

    void set(String iso) {
        now = Instant.parse(iso);
    }

    void advance(Duration d) {
        now = now.plus(d);
    }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
}
