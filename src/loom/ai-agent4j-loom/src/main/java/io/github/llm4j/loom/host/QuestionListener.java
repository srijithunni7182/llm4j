package io.github.llm4j.loom.host;

import java.util.List;

/**
 * Told when a run asks a person something and suspends, so the application can show the question
 * wherever it likes.
 */
@FunctionalInterface
public interface QuestionListener {
    void asked(String runId, String stepId, String prompt, List<String> choices);
}
