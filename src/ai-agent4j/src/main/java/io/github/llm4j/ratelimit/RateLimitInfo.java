package io.github.llm4j.ratelimit;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * What a provider's rate-limit refusal says: which limit was hit and, above all, <em>when it resets</em>.
 * The reset is always an absolute instant, so it stays meaningful when read hours later (from a run
 * journal, on another machine).
 *
 * @param resetAt   when the limit is expected to lift; never null
 * @param scope     which kind of limit
 * @param provider  e.g. {@code google}, {@code anthropic}, {@code openai}, or {@code budget:<name>}
 * @param quotaId   the provider's quota name, when it gives one
 * @param limit     the limit's size, when known
 * @param remaining what is left of it, when known
 * @param estimated true when the provider sent no reset information and {@code resetAt} is a guess
 * @param detail    the raw hint the reset was read from, for logs
 */
public record RateLimitInfo(
        Instant resetAt,
        Scope scope,
        String provider,
        String quotaId,
        Long limit,
        Long remaining,
        boolean estimated,
        String detail) {

    /** Which kind of limit was hit. */
    public enum Scope {
        REQUESTS,
        TOKENS,
        DAILY_QUOTA,
        UNKNOWN
    }

    public RateLimitInfo {
        Objects.requireNonNull(resetAt, "resetAt");
        if (scope == null) scope = Scope.UNKNOWN;
        if (provider == null) provider = "unknown";
    }

    public static RateLimitInfo at(Instant resetAt, Scope scope, String provider, String detail) {
        return new RateLimitInfo(resetAt, scope, provider, null, null, null, false, detail);
    }

    /** How long from {@code now} until the reset; never negative. */
    public Duration waitFrom(Instant now) {
        Duration d = Duration.between(now, resetAt);
        return d.isNegative() ? Duration.ZERO : d;
    }

    public RateLimitInfo withProvider(String provider) {
        return new RateLimitInfo(resetAt, scope, provider, quotaId, limit, remaining, estimated, detail);
    }

    /** A one-line description, e.g. {@code google daily quota (GenerateRequestsPerDay…)}. */
    public String describe() {
        String kind = switch (scope) {
            case REQUESTS -> "request rate limit";
            case TOKENS -> "token rate limit";
            case DAILY_QUOTA -> "daily quota";
            case UNKNOWN -> "rate limit";
        };
        return provider + " " + kind + (quotaId != null ? " (" + quotaId + ")" : "");
    }
}
