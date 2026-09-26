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
}
