package io.github.llm4j.loom.host;

import java.util.List;

/**
 * Told when a run asks a person something and suspends, so the application can show the question
 * wherever it likes. It is told once the run is recorded as suspended, so an answer given from
 * the listener itself ({@link LoomHost#answer}) is taken.
 */
@FunctionalInterface
public interface QuestionListener {
    void asked(String runId, String stepId, String prompt, List<String> choices);
}
