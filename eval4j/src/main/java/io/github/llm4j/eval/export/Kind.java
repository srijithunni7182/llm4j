package io.github.llm4j.eval.export;

/** How an evaluation's result is produced and read. */
public enum Kind {
    /** An LLM judge's 0..1 score. */
    JUDGE,
    /** An LLM A/B comparison: 1 B wins, 0.5 tie, 0 A wins. */
    PAIRWISE,
    /** A deterministic check: 1 held, 0 did not. Free to run. */
    ASSERTION,
    /** A measured value against a budget. */
    MEASURED
}
