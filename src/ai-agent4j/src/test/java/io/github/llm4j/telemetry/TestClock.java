package io.github.llm4j.telemetry;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/** Time that moves only when a test says so. */
final class TestClock extends Clock {
    private final AtomicLong millis = new AtomicLong(1_700_000_000_000L);

    void advance(long ms) {
        millis.addAndGet(ms);
    }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return Instant.ofEpochMilli(millis.get()); }
    @Override public long millis() { return millis.get(); }
}
