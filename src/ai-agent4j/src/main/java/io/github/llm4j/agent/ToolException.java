package io.github.llm4j.agent;

import io.github.llm4j.telemetry.ErrorCategory;
import java.util.Objects;

/**
 * Thrown by a tool to say <em>why</em> it failed, in the vocabulary statistics and dashboards use.
 * A tool that throws anything else is still classified, by the exception's type; this is for a tool
 * that knows better.
 *
 * <pre>{@code
 * throw new ToolException(ErrorCategory.TIMEOUT, "the search backend did not answer in 10 s");
 * }</pre>
 */
public class ToolException extends RuntimeException {

    private final ErrorCategory category;

    public ToolException(ErrorCategory category, String message) {
        super(message);
        this.category = Objects.requireNonNull(category, "category cannot be null");
    }

    public ToolException(ErrorCategory category, String message, Throwable cause) {
        super(message, cause);
        this.category = Objects.requireNonNull(category, "category cannot be null");
    }

    public ErrorCategory category() {
        return category;
    }
}
