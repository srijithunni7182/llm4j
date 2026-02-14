package io.github.llm4j.config;

import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Defines the retry behavior for failed API requests.
 * This class is immutable and thread-safe.
 */
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

    private RetryPolicy(Builder builder) {
        this.maxRetries = builder.maxRetries;
        this.backoffStrategy = builder.backoffStrategy;
        this.initialBackoff = builder.initialBackoff;
        this.maxBackoff = builder.maxBackoff;
        this.retryableStatusCodes = Collections.unmodifiableSet(new HashSet<>(builder.retryableStatusCodes));
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
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        RetryPolicy that = (RetryPolicy) o;
        return maxRetries == that.maxRetries &&
                backoffStrategy == that.backoffStrategy &&
                Objects.equals(initialBackoff, that.initialBackoff) &&
                Objects.equals(maxBackoff, that.maxBackoff) &&
                Objects.equals(retryableStatusCodes, that.retryableStatusCodes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxRetries, backoffStrategy, initialBackoff, maxBackoff, retryableStatusCodes);
    }

    @Override
    public String toString() {
        return "RetryPolicy{" +
                "maxRetries=" + maxRetries +
                ", backoffStrategy=" + backoffStrategy +
                ", initialBackoff=" + initialBackoff +
                ", maxBackoff=" + maxBackoff +
                ", retryableStatusCodes=" + retryableStatusCodes +
                '}';
    }

    public static final class Builder {
        private int maxRetries = 3;
        private BackoffStrategy backoffStrategy = BackoffStrategy.EXPONENTIAL;
        private Duration initialBackoff = Duration.ofMillis(500);
        private Duration maxBackoff = Duration.ofSeconds(10);
        private Set<Integer> retryableStatusCodes = new HashSet<>();

        private Builder() {
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
