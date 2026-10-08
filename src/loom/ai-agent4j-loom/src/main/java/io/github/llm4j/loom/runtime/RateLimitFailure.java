package io.github.llm4j.loom.runtime;

import io.github.llm4j.ratelimit.RateLimitInfo;

/** A step hit a rate limit (or budget window) and the script's policy is not to wait for it. */
public class RateLimitFailure extends RuntimeException {

    private final RateLimitInfo limit;

    public RateLimitFailure(RateLimitInfo limit, String why) {
        super("rate limited: " + limit.describe() + "; resets at " + limit.resetAt()
                + (limit.estimated() ? " (estimated)" : "") + (why == null ? "" : " — " + why));
        this.limit = limit;
    }

    public RateLimitInfo limit() {
        return limit;
    }
}
