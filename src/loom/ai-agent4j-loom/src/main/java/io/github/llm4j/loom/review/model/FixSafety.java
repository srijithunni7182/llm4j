package io.github.llm4j.loom.review.model;

/** How far a fix can be trusted to apply on its own. */
public enum FixSafety {
    /** Mechanical and local: it changes only what the finding's title says. */
    SAFE,
    /** It needs a person's choice, so it is shown and applied only on request. */
    REVIEW
}
