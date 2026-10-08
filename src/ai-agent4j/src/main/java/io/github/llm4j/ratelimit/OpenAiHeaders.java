package io.github.llm4j.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * OpenAI-style {@code x-ratelimit-{limit,remaining,reset}-{requests,tokens}} headers, with resets as
 * durations such as {@code 6m0s}. Also used by several OpenAI-compatible providers.
 */
public final class OpenAiHeaders implements RateLimitParser {

    @Override
    public Optional<RateLimitInfo> parse(Response response, Clock clock) {
        if (!response.hasHeaderPrefix("x-ratelimit-reset-")) return Optional.empty();
        RateLimitInfo exhausted = null;
        RateLimitInfo latest = null;
        for (String dim : new String[] {"requests", "tokens"}) {
            String raw = response.header("x-ratelimit-reset-" + dim);
            Duration wait = Durations.parse(raw);
            if (wait == null) continue;
            Long limit = AnthropicHeaders.number(response.header("x-ratelimit-limit-" + dim));
            Long remaining = AnthropicHeaders.number(response.header("x-ratelimit-remaining-" + dim));
            RateLimitInfo info = new RateLimitInfo(clock.instant().plus(wait),
                    dim.equals("requests") ? RateLimitInfo.Scope.REQUESTS : RateLimitInfo.Scope.TOKENS,
                    response.provider(), null, limit, remaining, false,
                    "x-ratelimit-reset-" + dim + ": " + raw);
            if (remaining != null && remaining == 0
                    && (exhausted == null || info.resetAt().isAfter(exhausted.resetAt()))) {
                exhausted = info;
            }
            if (latest == null || info.resetAt().isAfter(latest.resetAt())) latest = info;
        }
        return Optional.ofNullable(exhausted != null ? exhausted : latest);
    }
}
