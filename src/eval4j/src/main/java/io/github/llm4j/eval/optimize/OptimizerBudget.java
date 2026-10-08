package io.github.llm4j.eval.optimize;

import java.time.Duration;
import java.util.Objects;

/**
 * Hard caps on an optimization run. At least one cap must be set. A <em>rollout</em> is one
 * scenario run for one candidate; rollout and duration caps are checked before work starts.
 *
 * <p>The LLM-call cap counts rewriter calls plus calls made through {@link LlmCallCounter}s given
 * to the optimizer; it is checked before each rollout, so it can be exceeded by the calls of one
 * in-flight rollout.
 */
public final class OptimizerBudget {

    private final long maxRollouts;
    private final long maxRounds;
    private final long maxLlmCalls;
    private final Duration maxDuration;

    private OptimizerBudget(Builder b) {
        this.maxRollouts = b.maxRollouts;
        this.maxRounds = b.maxRounds;
        this.maxLlmCalls = b.maxLlmCalls;
        this.maxDuration = b.maxDuration;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 0 means unlimited. */
    public long maxRollouts() {
        return maxRollouts;
    }

    public long maxRounds() {
        return maxRounds;
    }

    public long maxLlmCalls() {
        return maxLlmCalls;
    }

    /** {@code null} means unlimited. */
    public Duration maxDuration() {
        return maxDuration;
    }

    public boolean hasAnyCap() {
        return maxRollouts > 0 || maxRounds > 0 || maxLlmCalls > 0 || maxDuration != null;
    }

    public static final class Builder {
        private long maxRollouts;
        private long maxRounds;
        private long maxLlmCalls;
        private Duration maxDuration;

        public Builder maxRollouts(long maxRollouts) {
            this.maxRollouts = requirePositive(maxRollouts, "maxRollouts");
            return this;
        }

        public Builder maxRounds(long maxRounds) {
            this.maxRounds = requirePositive(maxRounds, "maxRounds");
            return this;
        }

        public Builder maxLlmCalls(long maxLlmCalls) {
            this.maxLlmCalls = requirePositive(maxLlmCalls, "maxLlmCalls");
            return this;
        }

        public Builder maxDuration(Duration maxDuration) {
            Objects.requireNonNull(maxDuration, "maxDuration cannot be null");
            if (maxDuration.isZero() || maxDuration.isNegative()) {
                throw new IllegalArgumentException("maxDuration must be positive");
            }
            this.maxDuration = maxDuration;
            return this;
        }

        public OptimizerBudget build() {
            return new OptimizerBudget(this);
        }

        private static long requirePositive(long value, String name) {
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be positive, got: " + value);
            }
            return value;
        }
    }
}
