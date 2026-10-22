package io.github.llm4j.spring;

import java.util.List;

/**
 * Published when a workflow run asks a person something and suspends; answer it with {@link
 * LoomRuntime#answer}.
 */
public record HumanQuestionEvent(
        String runId, String stepId, String prompt, List<String> choices) {}
