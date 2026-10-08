package io.github.llm4j.config;

import io.github.llm4j.ratelimit.GoogleRpcBody;
import io.github.llm4j.ratelimit.Sleeper;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Defines the retry behavior for failed API requests. This class is immutable and thread-safe. */
public final class RetryPolicy {

    public enum BackoffStrategy {
        EXPONENTIAL,
        LINEAR,
        FIXED
    }

    private final int maxRetries;
    private final BackoffStrategy backoffStrategy;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final Set<Integer> retryableStatusCodes;
    private final Duration inlineWaitThreshold;
    private final Duration fallbackDelay;
    private final Duration maxFallbackDelay;
    private final ZoneId dailyResetZone;
    private final Clock clock;
    private final Sleeper sleeper;

    private RetryPolicy(Builder builder) {
        this.inlineWaitThreshold = builder.inlineWaitThreshold;
        this.fallbackDelay = builder.fallbackDelay;
        this.maxFallbackDelay = builder.maxFallbackDelay;
        this.dailyResetZone = builder.dailyResetZone;
        this.clock = builder.clock;
        this.sleeper = builder.sleeper;
        this.maxRetries = builder.maxRetries;
        this.backoffStrategy = builder.backoffStrategy;
        this.initialBackoff = builder.initialBackoff;
        this.maxBackoff = builder.maxBackoff;
        this.retryableStatusCodes =
                Collections.unmodifiableSet(new HashSet<>(builder.retryableStatusCodes));
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public BackoffStrategy getBackoffStrategy() {
        return backoffStrategy;
    }

    public Duration getInitialBackoff() {
        return initialBackoff;
    }

    public Duration getMaxBackoff() {
        return maxBackoff;
    }

    public Set<Integer> getRetryableStatusCodes() {
        return retryableStatusCodes;
    }

    /** A 429 whose limit resets within this long is waited out inline; longer ones are raised. Default 30 s. */
    public Duration getInlineWaitThreshold() {
        return inlineWaitThreshold;
    }

    /** Assumed wait when a 429 carries no reset information; doubles per consecutive 429. Default 60 s. */
    public Duration getFallbackDelay() {
        return fallbackDelay;
    }

    /** Upper bound for the doubling fallback. Default 1 h. */
    public Duration getMaxFallbackDelay() {
        return maxFallbackDelay;
    }

    /** Where per-day quotas reset at midnight (Google: Pacific time). */
    public ZoneId getDailyResetZone() {
        return dailyResetZone;
    }

    public Clock getClock() {
        return clock;
    }

    public Sleeper getSleeper() {
        return sleeper;
    }

    /**
     * Calculates the backoff duration for a given attempt.
     *
     * @param attempt the attempt number (0-based)
     * @return the duration to wait before retrying
     */
    public Duration calculateBackoff(int attempt) {
        long backoffMillis = initialBackoff.toMillis();

        switch (backoffStrategy) {
            case EXPONENTIAL:
                backoffMillis = (long) (backoffMillis * Math.pow(2, attempt));
                break;
            case LINEAR:
                backoffMillis = backoffMillis * (attempt + 1);
                break;
            case FIXED:
                // Keep initial backoff
                break;
        }

        return Duration.ofMillis(Math.min(backoffMillis, maxBackoff.toMillis()));
    }

    /**
     * Checks if a status code should trigger a retry.
     *
     * @param statusCode the HTTP status code
     * @return true if the status code is retryable
     */
    public boolean isRetryable(int statusCode) {
        return retryableStatusCodes.contains(statusCode);
    }

    /**
     * Creates a default retry policy with sensible defaults.
     *
     * @return a default retry policy
     */
    public static RetryPolicy defaultPolicy() {
        return builder()
                .maxRetries(3)
                .backoffStrategy(BackoffStrategy.EXPONENTIAL)
                .initialBackoff(Duration.ofMillis(500))
                .maxBackoff(Duration.ofSeconds(10))
                .addRetryableStatusCode(429) // Rate limit
                .addRetryableStatusCode(500) // Internal server error
                .addRetryableStatusCode(502) // Bad gateway
                .addRetryableStatusCode(503) // Service unavailable
                .addRetryableStatusCode(504) // Gateway timeout
                .addRetryableStatusCode(529) // Overloaded (Anthropic)
                .build();
    }

    /**
     * Creates a retry policy with no retries.
     *
     * @return a no-retry policy
     */
    public static RetryPolicy noRetry() {
        return builder().maxRetries(0).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RetryPolicy that = (RetryPolicy) o;
        return maxRetries == that.maxRetries
                && backoffStrategy == that.backoffStrategy
                && Objects.equals(initialBackoff, that.initialBackoff)
                && Objects.equals(maxBackoff, that.maxBackoff)
                && Objects.equals(retryableStatusCodes, that.retryableStatusCodes)
                && Objects.equals(inlineWaitThreshold, that.inlineWaitThreshold)
                && Objects.equals(fallbackDelay, that.fallbackDelay)
                && Objects.equals(maxFallbackDelay, that.maxFallbackDelay)
                && Objects.equals(dailyResetZone, that.dailyResetZone);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                maxRetries, backoffStrategy, initialBackoff, maxBackoff, retryableStatusCodes,
                inlineWaitThreshold, fallbackDelay, maxFallbackDelay, dailyResetZone);
    }

    @Override
    public String toString() {
        return "RetryPolicy{"
                + "maxRetries="
                + maxRetries
                + ", backoffStrategy="
                + backoffStrategy
                + ", initialBackoff="
                + initialBackoff
                + ", maxBackoff="
                + maxBackoff
                + ", retryableStatusCodes="
                + retryableStatusCodes
                + '}';
    }

    public static final class Builder {
        private int maxRetries = 3;
        private BackoffStrategy backoffStrategy = BackoffStrategy.EXPONENTIAL;
        private Duration initialBackoff = Duration.ofMillis(500);
        private Duration maxBackoff = Duration.ofSeconds(10);
        private Set<Integer> retryableStatusCodes = new HashSet<>();
        private Duration inlineWaitThreshold = Duration.ofSeconds(30);
        private Duration fallbackDelay = Duration.ofSeconds(60);
        private Duration maxFallbackDelay = Duration.ofHours(1);
        private ZoneId dailyResetZone = GoogleRpcBody.DEFAULT_DAILY_RESET_ZONE;
        private Clock clock = Clock.systemUTC();
        private Sleeper sleeper = Sleeper.SYSTEM;

        private Builder() {}

        public Builder inlineWaitThreshold(Duration threshold) {
            this.inlineWaitThreshold = Objects.requireNonNull(threshold);
            return this;
        }

        public Builder fallbackDelay(Duration delay) {
            this.fallbackDelay = Objects.requireNonNull(delay);
            return this;
        }

        public Builder maxFallbackDelay(Duration delay) {
            this.maxFallbackDelay = Objects.requireNonNull(delay);
            return this;
        }

        public Builder dailyResetZone(ZoneId zone) {
            this.dailyResetZone = Objects.requireNonNull(zone);
            return this;
        }

        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock);
            return this;
        }

        public Builder sleeper(Sleeper sleeper) {
            this.sleeper = Objects.requireNonNull(sleeper);
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder backoffStrategy(BackoffStrategy backoffStrategy) {
            this.backoffStrategy = backoffStrategy;
            return this;
        }

        public Builder initialBackoff(Duration initialBackoff) {
            this.initialBackoff = initialBackoff;
            return this;
        }

        public Builder maxBackoff(Duration maxBackoff) {
            this.maxBackoff = maxBackoff;
            return this;
        }

        public Builder retryableStatusCodes(Set<Integer> retryableStatusCodes) {
            this.retryableStatusCodes = new HashSet<>(retryableStatusCodes);
            return this;
        }

        public Builder addRetryableStatusCode(int statusCode) {
            this.retryableStatusCodes.add(statusCode);
            return this;
        }

        public RetryPolicy build() {
            return new RetryPolicy(this);
        }
    }
}
