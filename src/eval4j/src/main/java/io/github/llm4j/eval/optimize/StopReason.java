package io.github.llm4j.eval.optimize;

/** Why an optimization run ended. The first condition met wins. */
public enum StopReason {
    /** Best validation mean reached the target with no guardrail violations. */
    TARGET_REACHED,
    MAX_ROLLOUTS,
    MAX_LLM_CALLS,
    MAX_ROUNDS,
    MAX_DURATION,
    /** {@code patience} consecutive rounds added nothing to the frontier. */
    NO_PROGRESS,
    /** The run's thread was interrupted or cancelled. */
    CANCELLED,
    /** An unrecoverable error, e.g. the seed could not be scored or infrastructure kept failing. */
    FAILED
}
