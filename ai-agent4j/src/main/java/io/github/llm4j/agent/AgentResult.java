package io.github.llm4j.agent;

import io.github.llm4j.model.ConfidenceScore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Represents the result of a ReAct agent execution. */
public final class AgentResult {

    private final String finalAnswer;
    private final List<AgentStep> steps;
    private final int iterations;
    private final boolean completed;
    private final ConfidenceScore confidence;
    private final boolean uncertaintyDetected;
    private final String uncertaintyReason;
    private final Usage usage;
    private final int redundantActionCount;
    private final boolean protocolFollowed;

    private AgentResult(Builder builder) {
        this.finalAnswer = builder.finalAnswer;
        this.steps = Collections.unmodifiableList(new ArrayList<>(builder.steps));
        this.iterations = builder.iterations;
        this.completed = builder.completed;
        this.confidence = builder.confidence;
        this.uncertaintyDetected = builder.uncertaintyDetected;
        this.uncertaintyReason = builder.uncertaintyReason;
        this.usage = builder.usage != null ? builder.usage : Usage.EMPTY;
        this.redundantActionCount = builder.redundantActionCount;
        this.protocolFollowed = builder.protocolFollowed;
    }

    public String getFinalAnswer() {
        return finalAnswer;
    }

    public List<AgentStep> getSteps() {
        return steps;
    }

    public int getIterations() {
        return iterations;
    }

    public boolean isCompleted() {
        return completed;
    }

    public ConfidenceScore getConfidence() {
        return confidence;
    }

    public boolean isUncertaintyDetected() {
        return uncertaintyDetected;
    }

    public String getUncertaintyReason() {
        return uncertaintyReason;
    }

    /** Aggregate LLM usage across every model call this run made. Never null. */
    public Usage getUsage() {
        return usage;
    }

    /**
     * How many times the agent attempted to repeat an exact prior action+input pair within this
     * run. The agent blocks the repeat and feeds an error observation back to the model, but this
     * count is what lets a caller detect looping/dithering behavior as data instead of scanning
     * observation text.
     */
    public int getRedundantActionCount() {
        return redundantActionCount;
    }

    /**
     * Whether the model's raw output was ever successfully parsed as the expected
     * thought/action/final-answer format during this run. {@code false} means at least one
     * iteration fell back to treating the model's entire raw output as the final answer because it
     * matched neither the JSON nor legacy response format — i.e. the model ignored the response
     * protocol entirely. {@link #isCompleted()} can still be {@code true} in that case (a final
     * answer of some form was produced), so check this separately if protocol compliance matters.
     */
    public boolean isProtocolFollowed() {
        return protocolFollowed;
    }

    public boolean isHighConfidence() {
        return confidence != null && confidence.isHigh();
    }

    public boolean shouldEscalateToHuman() {
        return (confidence != null && confidence.isLow()) || uncertaintyDetected;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return "AgentResult{"
                + "finalAnswer='"
                + finalAnswer
                + '\''
                + ", iterations="
                + iterations
                + ", completed="
                + completed
                + ", steps="
                + steps.size()
                + '}';
    }

    /**
     * What actually happened when the agent attempted a step's action. A step exists in {@link
     * #getSteps()} whenever the model requested an action, regardless of whether it ran — check
     * this before treating a step as evidence the tool actually executed. In particular, a human
     * rejecting an action via a Human-in-the-Loop {@code ApprovalCallback} still produces a step,
     * with outcome {@link #REJECTED_BY_HUMAN}, not {@link #EXECUTED}.
     */
    public enum StepOutcome {
        /** The tool ran and returned an observation. */
        EXECUTED,
        /** The model named a tool that isn't registered. */
        UNKNOWN_TOOL,
        /** Blocked as an exact repeat of a prior action+input pair (loop detection). */
        DUPLICATE_BLOCKED,
        /** The tool requires approval but no {@code ApprovalCallback} was configured. */
        APPROVAL_UNAVAILABLE,
        /** A human reviewer rejected the action via the configured {@code ApprovalCallback}. */
        REJECTED_BY_HUMAN,
        /** The tool was approved (or required none) but threw while executing. */
        EXECUTION_ERROR
    }

    /** Represents a single step in the agent's reasoning process. */
    public static final class AgentStep {
        private final String thought;
        private final String action;
        private final String actionInput;
        private final String observation;
        private final ConfidenceScore stepConfidence;
        private final Instant timestamp;
        private final StepOutcome outcome;

        public AgentStep(String thought, String action, String actionInput, String observation) {
            this(thought, action, actionInput, observation, null, Instant.now());
        }

        /**
         * @param outcome what actually happened when this action was attempted; see {@link
         *     StepOutcome}
         */
        public AgentStep(
                String thought,
                String action,
                String actionInput,
                String observation,
                StepOutcome outcome) {
            this(thought, action, actionInput, observation, null, Instant.now(), outcome);
        }

        public AgentStep(
                String thought,
                String action,
                String actionInput,
                String observation,
                ConfidenceScore stepConfidence,
                Instant timestamp) {
            this(thought, action, actionInput, observation, stepConfidence, timestamp, StepOutcome.EXECUTED);
        }

        public AgentStep(
                String thought,
                String action,
                String actionInput,
                String observation,
                ConfidenceScore stepConfidence,
                Instant timestamp,
                StepOutcome outcome) {
            this.thought = thought;
            this.action = action;
            this.actionInput = actionInput;
            this.observation = observation;
            this.stepConfidence = stepConfidence;
            this.timestamp = timestamp != null ? timestamp : Instant.now();
            this.outcome = outcome != null ? outcome : StepOutcome.EXECUTED;
        }

        public String getThought() {
            return thought;
        }

        public String getAction() {
            return action;
        }

        public String getActionInput() {
            return actionInput;
        }

        public String getObservation() {
            return observation;
        }

        public ConfidenceScore getStepConfidence() {
            return stepConfidence;
        }

        public Instant getTimestamp() {
            return timestamp;
        }

        /** What actually happened when this action was attempted. Never null. */
        public StepOutcome getOutcome() {
            return outcome;
        }

        @Override
        public String toString() {
            return "AgentStep{"
                    + "thought='"
                    + thought
                    + '\''
                    + ", action='"
                    + action
                    + '\''
                    + ", actionInput='"
                    + actionInput
                    + '\''
                    + ", observation='"
                    + observation
                    + '\''
                    + ", outcome="
                    + outcome
                    + '}';
        }
    }

    /** Aggregate LLM call/token usage across a full agent run. */
    public static final class Usage {
        static final Usage EMPTY = new Usage(0, 0, 0, 0);

        private final int llmCalls;
        private final int promptTokens;
        private final int completionTokens;
        private final int totalTokens;

        public Usage(int llmCalls, int promptTokens, int completionTokens, int totalTokens) {
            this.llmCalls = llmCalls;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
        }

        public int getLlmCalls() {
            return llmCalls;
        }

        public int getPromptTokens() {
            return promptTokens;
        }

        public int getCompletionTokens() {
            return completionTokens;
        }

        public int getTotalTokens() {
            return totalTokens;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Usage that = (Usage) o;
            return llmCalls == that.llmCalls
                    && promptTokens == that.promptTokens
                    && completionTokens == that.completionTokens
                    && totalTokens == that.totalTokens;
        }

        @Override
        public int hashCode() {
            return Objects.hash(llmCalls, promptTokens, completionTokens, totalTokens);
        }

        @Override
        public String toString() {
            return "Usage{"
                    + "llmCalls="
                    + llmCalls
                    + ", promptTokens="
                    + promptTokens
                    + ", completionTokens="
                    + completionTokens
                    + ", totalTokens="
                    + totalTokens
                    + '}';
        }
    }

    public static final class Builder {
        private String finalAnswer;
        private List<AgentStep> steps = new ArrayList<>();
        private int iterations;
        private boolean completed;
        private ConfidenceScore confidence;
        private boolean uncertaintyDetected;
        private String uncertaintyReason;
        private Usage usage;
        private int redundantActionCount;
        private boolean protocolFollowed = true;

        private Builder() {}

        public Builder finalAnswer(String finalAnswer) {
            this.finalAnswer = finalAnswer;
            return this;
        }

        public Builder steps(List<AgentStep> steps) {
            this.steps = new ArrayList<>(steps);
            return this;
        }

        public Builder addStep(AgentStep step) {
            this.steps.add(step);
            return this;
        }

        public Builder iterations(int iterations) {
            this.iterations = iterations;
            return this;
        }

        public Builder completed(boolean completed) {
            this.completed = completed;
            return this;
        }

        public Builder confidence(ConfidenceScore confidence) {
            this.confidence = confidence;
            return this;
        }

        public Builder uncertaintyDetected(boolean uncertaintyDetected) {
            this.uncertaintyDetected = uncertaintyDetected;
            return this;
        }

        public Builder uncertaintyReason(String uncertaintyReason) {
            this.uncertaintyReason = uncertaintyReason;
            return this;
        }

        public Builder usage(Usage usage) {
            this.usage = usage;
            return this;
        }

        public Builder redundantActionCount(int redundantActionCount) {
            this.redundantActionCount = redundantActionCount;
            return this;
        }

        public Builder protocolFollowed(boolean protocolFollowed) {
            this.protocolFollowed = protocolFollowed;
            return this;
        }

        public AgentResult build() {
            return new AgentResult(this);
        }
    }
}
