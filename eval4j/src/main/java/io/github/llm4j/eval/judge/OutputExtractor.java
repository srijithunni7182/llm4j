package io.github.llm4j.eval.judge;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.model.LLMResponse;

/** Resolves the text to grade out of whatever eval4j assertion type is being evaluated. */
final class OutputExtractor {

    private OutputExtractor() {}

    static String extract(Object actual) {
        if (actual instanceof AgentResult agentResult) {
            return agentResult.getFinalAnswer();
        }
        if (actual instanceof LLMResponse llmResponse) {
            return llmResponse.getContent();
        }
        if (actual instanceof String text) {
            return text;
        }
        throw new IllegalArgumentException(
                "Expected an AgentResult, LLMResponse, or String, but got: "
                        + (actual == null ? "null" : actual.getClass().getName()));
    }

    /**
     * Renders the full step-by-step trajectory (thought/action/input/observation/outcome per step)
     * for an {@link AgentResult}, or {@code null} if {@code actual} isn't one or has no steps.
     * Judging only {@link #extract(Object)} (the final answer) misses everything the agent actually
     * did to get there — some criteria, like task completion, need the whole run.
     */
    static String extractTrajectory(Object actual) {
        if (!(actual instanceof AgentResult agentResult) || agentResult.getSteps().isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (AgentResult.AgentStep step : agentResult.getSteps()) {
            sb.append("Step ").append(i++).append(":\n");
            if (step.getThought() != null) {
                sb.append("  Thought: ").append(step.getThought()).append('\n');
            }
            sb.append("  Action: ").append(step.getAction()).append('\n');
            if (step.getActionInput() != null) {
                sb.append("  Action Input: ").append(step.getActionInput()).append('\n');
            }
            sb.append("  Outcome: ").append(step.getOutcome()).append('\n');
            sb.append("  Observation: ").append(step.getObservation()).append('\n');
        }
        return sb.toString();
    }
}
