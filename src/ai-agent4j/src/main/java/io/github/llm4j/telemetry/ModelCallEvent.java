package io.github.llm4j.telemetry;

import io.github.llm4j.model.LLMResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * One call to a model, as the agent saw it. Numbers and names only: never the prompt or the answer.
 *
 * @param iteration the agent iteration (0-based) the call belongs to
 * @param requestedModel the model the request named, or null
 * @param model the model that answered, or null when the call failed
 * @param startedAt when the call began
 * @param durationMillis wall time of the call, retries and waits included
 * @param waitedMillis time spent sleeping between attempts (a back-off, a rate-limit wait)
 * @param attempts requests sent (1 when nothing was retried)
 * @param failovers the clients that failed before one answered, in order
 * @param promptTokens tokens sent, as reported by the provider or estimated
 * @param completionTokens tokens received
 * @param totalTokens prompt plus completion
 * @param usageEstimated whether the token counts are an estimate
 * @param cost the cost of the call, or null when unpriced
 * @param finishReason why the model stopped, or null when the call failed
 * @param toolCallsRequested how many tool calls the answer asked for
 * @param outcome whether the call produced an answer
 * @param category why it failed, or null
 * @param errorType the exception's simple class name, or null
 */
public record ModelCallEvent(
        int iteration,
        String requestedModel,
        String model,
        Instant startedAt,
        long durationMillis,
        long waitedMillis,
        int attempts,
        List<String> failovers,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        boolean usageEstimated,
        BigDecimal cost,
        LLMResponse.FinishReason finishReason,
        int toolCallsRequested,
        Outcome outcome,
        ErrorCategory category,
        String errorType) {

    public enum Outcome {
        OK,
        FAILED
    }

    public ModelCallEvent {
        if (attempts < 1)
            throw new IllegalArgumentException("attempts must be at least 1, got " + attempts);
        if (durationMillis < 0)
            throw new IllegalArgumentException(
                    "durationMillis must not be negative, got " + durationMillis);
        if (waitedMillis < 0)
            throw new IllegalArgumentException(
                    "waitedMillis must not be negative, got " + waitedMillis);
        if (waitedMillis > durationMillis)
            throw new IllegalArgumentException(
                    "waitedMillis ("
                            + waitedMillis
                            + ") cannot exceed durationMillis ("
                            + durationMillis
                            + ")");
        failovers = failovers == null ? List.of() : List.copyOf(failovers);
        if (outcome == null) outcome = Outcome.OK;
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean failed() {
        return outcome == Outcome.FAILED;
    }

    /** Whether the answer was cut off by the token limit. */
    public boolean truncated() {
        return finishReason == LLMResponse.FinishReason.LENGTH;
    }

    /** Time not spent waiting between attempts. */
    public long processingMillis() {
        return durationMillis - waitedMillis;
    }

    /** Attempts beyond the first. */
    public int retries() {
        return attempts - 1;
    }

    public static final class Builder {
        private int iteration;
        private String requestedModel;
        private String model;
        private Instant startedAt;
        private long durationMillis;
        private long waitedMillis;
        private int attempts = 1;
        private List<String> failovers = List.of();
        private long promptTokens;
        private long completionTokens;
        private long totalTokens;
        private boolean usageEstimated;
        private BigDecimal cost;
        private LLMResponse.FinishReason finishReason;
        private int toolCallsRequested;
        private Outcome outcome = Outcome.OK;
        private ErrorCategory category;
        private String errorType;

        private Builder() {}

        public Builder iteration(int v) {
            this.iteration = v;
            return this;
        }

        public Builder requestedModel(String v) {
            this.requestedModel = v;
            return this;
        }

        public Builder model(String v) {
            this.model = v;
            return this;
        }

        public Builder startedAt(Instant v) {
            this.startedAt = v;
            return this;
        }

        public Builder durationMillis(long v) {
            this.durationMillis = v;
            return this;
        }

        public Builder waitedMillis(long v) {
            this.waitedMillis = v;
            return this;
        }

        public Builder attempts(int v) {
            this.attempts = v;
            return this;
        }

        public Builder failovers(List<String> v) {
            this.failovers = v;
            return this;
        }

        public Builder promptTokens(long v) {
            this.promptTokens = v;
            return this;
        }

        public Builder completionTokens(long v) {
            this.completionTokens = v;
            return this;
        }

        public Builder totalTokens(long v) {
            this.totalTokens = v;
            return this;
        }

        public Builder usageEstimated(boolean v) {
            this.usageEstimated = v;
            return this;
        }

        public Builder cost(BigDecimal v) {
            this.cost = v;
            return this;
        }

        public Builder finishReason(LLMResponse.FinishReason v) {
            this.finishReason = v;
            return this;
        }

        public Builder toolCallsRequested(int v) {
            this.toolCallsRequested = v;
            return this;
        }

        /** Marks the call as failed, with why. */
        public Builder failed(ErrorCategory category, String errorType) {
            this.outcome = Outcome.FAILED;
            this.category = category;
            this.errorType = errorType;
            return this;
        }

        public ModelCallEvent build() {
            return new ModelCallEvent(
                    iteration,
                    requestedModel,
                    model,
                    startedAt,
                    durationMillis,
                    waitedMillis,
                    attempts,
                    failovers,
                    promptTokens,
                    completionTokens,
                    totalTokens,
                    usageEstimated,
                    cost,
                    finishReason,
                    toolCallsRequested,
                    outcome,
                    category,
                    errorType);
        }
    }
}
