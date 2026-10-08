package io.github.llm4j.ratelimit;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Anthropic's {@code anthropic-ratelimit-<dimension>-{limit,remaining,reset}} headers, with ISO-8601
 * reset times. Picks the dimension that is used up; if none reports zero remaining, the latest reset.
 */
public final class AnthropicHeaders implements RateLimitParser {

    private static final String[] DIMENSIONS = {"requests", "tokens", "input-tokens", "output-tokens"};

    @Override
    public Optional<RateLimitInfo> parse(Response response, Clock clock) {
        if (!response.hasHeaderPrefix("anthropic-ratelimit-")) return Optional.empty();
        RateLimitInfo exhausted = null;
        RateLimitInfo latest = null;
        for (String dim : DIMENSIONS) {
            String prefix = "anthropic-ratelimit-" + dim + "-";
            Instant reset = instant(response.header(prefix + "reset"));
            if (reset == null) continue;
            Long limit = number(response.header(prefix + "limit"));
            Long remaining = number(response.header(prefix + "remaining"));
            RateLimitInfo info = new RateLimitInfo(reset,
                    dim.equals("requests") ? RateLimitInfo.Scope.REQUESTS : RateLimitInfo.Scope.TOKENS,
                    response.provider(), null, limit, remaining, false,
                    prefix + "reset: " + response.header(prefix + "reset"));
            if (remaining != null && remaining == 0 && (exhausted == null || reset.isAfter(exhausted.resetAt()))) {
                exhausted = info;
            }
            if (latest == null || reset.isAfter(latest.resetAt())) latest = info;
        }
        return Optional.ofNullable(exhausted != null ? exhausted : latest);
    }

    static Instant instant(String value) {
        if (value == null) return null;
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (RuntimeException e) {
            try {
                return Instant.parse(value);
            } catch (RuntimeException e2) {
                return null;
            }
        }
    }

    static Long number(String value) {
        if (value == null) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
