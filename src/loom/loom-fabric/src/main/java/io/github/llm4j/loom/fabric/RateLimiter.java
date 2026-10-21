package io.github.llm4j.loom.fabric;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A token bucket per client: a burst, then a steady rate. Over it the server answers 429 and the
 * process backs off.
 */
final class RateLimiter {

    private static final class Bucket {
        double tokens;
        long at;
    }

    private final Clock clock;
    private final double perSecond;
    private final double burst;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    RateLimiter(Clock clock, double perSecond, double burst) {
        this.clock = clock;
        this.perSecond = perSecond;
        this.burst = burst;
    }

    /** True when the request may go ahead. */
    boolean allow(String client) {
        Bucket b =
                buckets.computeIfAbsent(
                        client,
                        k -> {
                            Bucket n = new Bucket();
                            n.tokens = burst;
                            n.at = clock.millis();
                            return n;
                        });
        synchronized (b) {
            long now = clock.millis();
            b.tokens = Math.min(burst, b.tokens + (now - b.at) * perSecond / 1000.0);
            b.at = now;
            if (b.tokens < 1) return false;
            b.tokens -= 1;
            return true;
        }
    }
}
