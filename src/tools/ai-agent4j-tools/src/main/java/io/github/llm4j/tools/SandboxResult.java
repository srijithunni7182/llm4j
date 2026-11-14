package io.github.llm4j.tools;

import io.github.llm4j.agent.tool.Isolation;
import java.util.Objects;

/**
 * What a sandboxed run did (D6): its exit code, whether it ran out of time, its output (the start
 * and the end kept when it was too long, and how much was cut), and the isolation the backend
 * actually applied.
 */
public record SandboxResult(
        int exit,
        boolean timedOut,
        String stdout,
        String stderr,
        boolean truncated,
        long cutBytes,
        Isolation isolation,
        long durationMs) {

    /** Checks that nothing is missing. */
    public SandboxResult {
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
        Objects.requireNonNull(isolation, "isolation");
    }
}
