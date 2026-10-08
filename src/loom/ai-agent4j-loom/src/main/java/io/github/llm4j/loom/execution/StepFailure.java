package io.github.llm4j.loom.execution;

/**
 * A step failed for a reason retrying won't fix — a guard blocked it, or its speech couldn't be made.
 * Handled like any failed step ({@code on_failure} with {@code _error}), but never retried.
 */
final class StepFailure extends RuntimeException {
    StepFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
