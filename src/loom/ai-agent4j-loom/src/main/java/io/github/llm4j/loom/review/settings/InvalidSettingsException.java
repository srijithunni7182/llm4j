package io.github.llm4j.loom.review.settings;

import java.util.List;

/**
 * {@code review.properties} (or a command-line setting) could not be used; every problem is listed.
 */
public final class InvalidSettingsException extends RuntimeException {

    private final List<String> problems;

    public InvalidSettingsException(List<String> problems) {
        super(String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
