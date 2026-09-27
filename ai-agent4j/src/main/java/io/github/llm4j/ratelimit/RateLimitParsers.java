package io.github.llm4j.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tries parsers in order — provider headers, then Google's structured body, then {@code Retry-After} —
 * and, when none applies, falls back to an estimated reset that backs off on repeated 429s from the
 * same provider (60 s, 120 s, 240 s … up to 1 h), cleared by a success.
 */
public final class RateLimitParsers {

    private final List<RateLimitParser> parsers;
    private final Duration fallbackDelay;
    private final Duration maxFallbackDelay;
    private final Map<String, Integer> consecutive = new ConcurrentHashMap<>();

    public RateLimitParsers(List<RateLimitParser> parsers, Duration fallbackDelay, Duration maxFallbackDelay) {
        this.parsers = List.copyOf(parsers);
        this.fallbackDelay = fallbackDelay;
        this.maxFallbackDelay = maxFallbackDelay;
    }

    public static RateLimitParsers standard() {
        return standard(GoogleRpcBody.DEFAULT_DAILY_RESET_ZONE, Duration.ofSeconds(60), Duration.ofHours(1));
    }

    public static RateLimitParsers standard(ZoneId dailyResetZone, Duration fallbackDelay, Duration maxFallbackDelay) {
        return new RateLimitParsers(
                List.of(new AnthropicHeaders(), new OpenAiHeaders(), new GoogleRpcBody(dailyResetZone),
                        new RetryAfterParser()),
                fallbackDelay, maxFallbackDelay);
    }

    /** Rate-limit information for a 429 response; always returns something. */
    public RateLimitInfo parse(RateLimitParser.Response response, Clock clock) {
        for (RateLimitParser p : parsers) {
            Optional<RateLimitInfo> info;
            try {
                info = p.parse(response, clock);
            } catch (RuntimeException e) {
                continue; // a parser must never break error handling
            }
            if (info.isPresent()) {
                consecutive.remove(response.provider());
                return info.get();
            }
        }
        int n = consecutive.merge(response.provider(), 1, Integer::sum);
        Duration wait = fallbackDelay;
        for (int i = 1; i < n && wait.compareTo(maxFallbackDelay) < 0; i++) wait = wait.multipliedBy(2);
        if (wait.compareTo(maxFallbackDelay) > 0) wait = maxFallbackDelay;
        return new RateLimitInfo(clock.instant().plus(wait), RateLimitInfo.Scope.UNKNOWN, response.provider(),
                null, null, null, true, "no reset information; estimated " + wait.toSeconds() + "s");
    }

    /** A request to this provider succeeded: the fallback backoff starts over. */
    public void success(String provider) {
        consecutive.remove(provider);
    }
}
