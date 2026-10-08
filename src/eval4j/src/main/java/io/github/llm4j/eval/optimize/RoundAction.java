package io.github.llm4j.eval.optimize;

/** What happened in one optimization round. */
public enum RoundAction {
    /** The parent already scored perfectly on the batch; nothing to learn, no rewrite made. */
    SKIPPED_PERFECT,
    /** The rewriter failed or returned unusable output. */
    REWRITE_FAILED,
    /** The proposal violated a {@link PromptConstraints} rule; no rollouts were spent on it. */
    REJECTED_CONSTRAINT,
    /** The proposal equalled a candidate already seen; no rollouts were spent on it. */
    REJECTED_DUPLICATE,
    /** The child did not beat its parent on the training batch. */
    GATE_FAILED,
    /** The child beat its parent, was scored on validation and joined the pool. */
    ACCEPTED
}
