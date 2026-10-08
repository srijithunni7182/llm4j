package io.github.llm4j.eval.export;

/** Whether an evaluation produced a result. */
public enum EvalStatus {
    /** A result exists. */
    EVALUATED,
    /** Expected but skipped (profile, budget, sampling). Never counts as passed or failed. */
    NOT_EVALUATED,
    /** The evaluation itself failed, for example the judge errored. */
    ERROR
}
