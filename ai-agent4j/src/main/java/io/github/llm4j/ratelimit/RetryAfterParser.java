package io.github.llm4j.ratelimit;

import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/** The standard {@code Retry-After} header: delay in seconds, or an HTTP-date. */
public final class RetryAfterParser implements RateLimitParser {

    @Override
    public Optional<RateLimitInfo> parse(Response response, Clock clock) {
        String value = response.header("Retry-After");
        if (value == null) return Optional.empty();
        Instant reset = null;
        try {
            double seconds = Double.parseDouble(value);
            if (seconds >= 0 && !Double.isInfinite(seconds)) {
                reset = clock.instant().plusMillis((long) Math.ceil(seconds * 1000));
            }
        } catch (NumberFormatException notSeconds) {
            try {
                reset = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            } catch (RuntimeException notADate) {
                return Optional.empty();
            }
        }
        if (reset == null) return Optional.empty();
        return Optional.of(RateLimitInfo.at(reset, RateLimitInfo.Scope.UNKNOWN, response.provider(),
                "Retry-After: " + value));
    }
}
