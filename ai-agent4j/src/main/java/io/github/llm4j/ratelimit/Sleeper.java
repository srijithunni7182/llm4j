package io.github.llm4j.ratelimit;

import java.time.Duration;

/** Waits. Injectable so tests can wait "an hour" instantly and assert exactly how long was waited. */
@FunctionalInterface
public interface Sleeper {

    Sleeper SYSTEM = d -> Thread.sleep(d.toMillis());

    void sleep(Duration duration) throws InterruptedException;
}
