package io.github.llm4j.spring;

import io.github.llm4j.config.RetryPolicy;
import io.github.llm4j.ratelimit.Sleeper;
import java.time.Clock;
import java.util.Set;

/**
 * {@code llm4j.clients.<name>.retry.*} as a {@link RetryPolicy}: what is set replaces the library's
 * default, what is not keeps it.
 */
final class RetryPolicies {

    private static final Set<Integer> DEFAULT_CODES = Set.of(429, 500, 502, 503, 504, 529);

    private RetryPolicies() {}

    static RetryPolicy from(Llm4jProperties.Retry r, Clock clock, Sleeper sleeper) {
        if (r != null && Boolean.FALSE.equals(r.enabled())) return RetryPolicy.noRetry();
        boolean custom =
                clock != null
                        || sleeper != null
                        || (r != null
                                && (r.maxRetries() != null
                                        || r.initialBackoff() != null
                                        || r.maxBackoff() != null
                                        || r.retryableStatusCodes() != null
                                        || r.inlineWaitThreshold() != null
                                        || r.fallbackDelay() != null
                                        || r.maxFallbackDelay() != null
                                        || r.dailyResetZone() != null));
        if (!custom) return RetryPolicy.defaultPolicy();
        RetryPolicy.Builder b = RetryPolicy.builder();
        for (int code :
                r != null && r.retryableStatusCodes() != null
                        ? r.retryableStatusCodes()
                        : DEFAULT_CODES) b.addRetryableStatusCode(code);
        if (r != null) {
            if (r.maxRetries() != null) b.maxRetries(r.maxRetries());
            if (r.initialBackoff() != null) b.initialBackoff(r.initialBackoff());
            if (r.maxBackoff() != null) b.maxBackoff(r.maxBackoff());
            if (r.inlineWaitThreshold() != null) b.inlineWaitThreshold(r.inlineWaitThreshold());
            if (r.fallbackDelay() != null) b.fallbackDelay(r.fallbackDelay());
            if (r.maxFallbackDelay() != null) b.maxFallbackDelay(r.maxFallbackDelay());
            if (r.dailyResetZone() != null) b.dailyResetZone(r.dailyResetZone());
        }
        if (clock != null) b.clock(clock);
        if (sleeper != null) b.sleeper(sleeper);
        return b.build();
    }
}
