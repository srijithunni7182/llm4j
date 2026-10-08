package io.github.llm4j.exception;

import io.github.llm4j.ratelimit.RateLimitInfo;
import java.time.Duration;
import java.time.Instant;

/**
 * A provider refused a request because of a rate limit or quota (HTTP 429). When the provider said when
 * the limit resets, {@link #info()} carries it as an absolute instant.
 */
public class RateLimitException extends LLMException {

    private final Long retryAfterSeconds;
    private final RateLimitInfo info;

    public RateLimitException(String message) {
        super(message, 429);
        this.retryAfterSeconds = null;
        this.info = null;
    }

    public RateLimitException(String message, Long retryAfterSeconds) {
        super(message, 429);
        this.retryAfterSeconds = retryAfterSeconds;
        this.info = null;
    }

    /** @param now the time the refusal was received, used for {@link #getRetryAfterSeconds()} */
    public RateLimitException(RateLimitInfo info, Instant now) {
        this(info, now, null);
    }

    public RateLimitException(RateLimitInfo info, Instant now, String body) {
        super("Rate limited: " + info.describe() + "; resets at " + info.resetAt()
                + (info.estimated() ? " (estimated)" : "")
                + (body == null || body.isBlank() ? "" : ": " + body), 429);
        this.info = info;
        Duration wait = info.waitFrom(now);
        this.retryAfterSeconds = (wait.toMillis() + 999) / 1000;
    }

    /** Seconds to wait from when the refusal was received, or null if unknown. */
    public Long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    /** What the provider said about the limit, or null for exceptions built without it. */
    public RateLimitInfo info() {
        return info;
    }

    /** {@link #info()}, or an estimate from {@code Retry-After} seconds, or {@code now + fallback}. */
    public RateLimitInfo infoOrEstimate(Instant now, Duration fallback) {
        if (info != null) return info;
        if (retryAfterSeconds != null) {
            return RateLimitInfo.at(now.plusSeconds(retryAfterSeconds), RateLimitInfo.Scope.UNKNOWN, "unknown",
                    "retryAfterSeconds " + retryAfterSeconds);
        }
        return new RateLimitInfo(now.plus(fallback), RateLimitInfo.Scope.UNKNOWN, "unknown", null, null, null,
                true, getMessage());
    }
}
