package io.github.llm4j.telemetry;

import java.time.Instant;
import java.util.Objects;

/**
 * One tool call, as the agent saw it. Numbers and names only: never the arguments or the result.
 *
 * @param iteration the agent iteration (0-based) the call belongs to
 * @param tool the tool's name as the model gave it
 * @param startedAt when the call began
 * @param durationMillis how long the tool's own {@code execute} took; zero for a call that never
 *     reached it
 * @param approvalMillis how long a person took to approve or reject, when approval was asked for
 * @param outcome what happened to the call
 * @param softFailure whether the tool returned normally but its result says it failed
 * @param category why it failed, or null when it did not
 * @param errorType the exception's simple class name when the tool threw, else null
 * @param argumentChars size of the arguments as the model wrote them
 * @param resultChars size of the result or of the error text
 */
public record ToolCallEvent(
        int iteration,
        String tool,
        Instant startedAt,
        long durationMillis,
        long approvalMillis,
        ToolOutcome outcome,
        boolean softFailure,
        ErrorCategory category,
        String errorType,
        int argumentChars,
        int resultChars) {

    public ToolCallEvent {
        Objects.requireNonNull(tool, "tool cannot be null");
        if (durationMillis < 0)
            throw new IllegalArgumentException(
                    "durationMillis must not be negative, got " + durationMillis);
        if (approvalMillis < 0)
            throw new IllegalArgumentException(
                    "approvalMillis must not be negative, got " + approvalMillis);
        if (outcome == null) outcome = ToolOutcome.EXECUTED;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Whether the call did not do what was asked: it did not run, it threw, or it returned an error
     * as text.
     */
    public boolean failed() {
        return outcome != ToolOutcome.EXECUTED || softFailure;
    }

    /**
     * Whether the tool's own code was reached, so that {@link #durationMillis()} is a measurement
     * of it.
     */
    public boolean ran() {
        return outcome.ran();
    }

    public static final class Builder {
        private int iteration;
        private String tool;
        private Instant startedAt;
        private long durationMillis;
        private long approvalMillis;
        private ToolOutcome outcome = ToolOutcome.EXECUTED;
        private boolean softFailure;
        private ErrorCategory category;
        private String errorType;
        private int argumentChars;
        private int resultChars;

        private Builder() {}

        public Builder iteration(int v) {
            this.iteration = v;
            return this;
        }

        public Builder tool(String v) {
            this.tool = v;
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

        public Builder approvalMillis(long v) {
            this.approvalMillis = v;
            return this;
        }

        public Builder argumentChars(int v) {
            this.argumentChars = v;
            return this;
        }

        public Builder resultChars(int v) {
            this.resultChars = v;
            return this;
        }

        /**
         * The call did not run to a result: what happened, why, and the exception's type if there
         * was one.
         */
        public Builder outcome(ToolOutcome outcome, ErrorCategory category, String errorType) {
            this.outcome = outcome;
            this.category = category;
            this.errorType = errorType;
            return this;
        }

        /** The tool returned normally, but its result is an error. */
        public Builder softFailure(ErrorCategory category) {
            this.softFailure = true;
            this.category = category;
            return this;
        }

        public ToolCallEvent build() {
            return new ToolCallEvent(
                    iteration,
                    tool,
                    startedAt,
                    durationMillis,
                    approvalMillis,
                    outcome,
                    softFailure,
                    category,
                    errorType,
                    argumentChars,
                    resultChars);
        }
    }
}
