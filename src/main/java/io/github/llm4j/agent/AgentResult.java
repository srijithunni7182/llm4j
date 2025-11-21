package io.github.llm4j.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents the result of a ReAct agent execution.
 */
public final class AgentResult {

    private final String finalAnswer;
    private final List<AgentStep> steps;
    private final int iterations;
    private final boolean completed;

    private AgentResult(Builder builder) {
        this.finalAnswer = builder.finalAnswer;
        this.steps = Collections.unmodifiableList(new ArrayList<>(builder.steps));
        this.iterations = builder.iterations;
        this.completed = builder.completed;
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

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return "AgentResult{" +
                "finalAnswer='" + finalAnswer + '\'' +
                ", iterations=" + iterations +
                ", completed=" + completed +
                ", steps=" + steps.size() +
                '}';
    }

    /**
     * Represents a single step in the agent's reasoning process.
     */
    public static final class AgentStep {
        private final String thought;
        private final String action;
        private final String actionInput;
        private final String observation;

        public AgentStep(String thought, String action, String actionInput, String observation) {
            this.thought = thought;
            this.action = action;
            this.actionInput = actionInput;
            this.observation = observation;
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

        @Override
        public String toString() {
            return "AgentStep{" +
                    "thought='" + thought + '\'' +
                    ", action='" + action + '\'' +
                    ", actionInput='" + actionInput + '\'' +
                    ", observation='" + observation + '\'' +
                    '}';
        }
    }

    public static final class Builder {
        private String finalAnswer;
        private List<AgentStep> steps = new ArrayList<>();
        private int iterations;
        private boolean completed;

        private Builder() {
        }

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

        public AgentResult build() {
            return new AgentResult(this);
        }
    }
}
