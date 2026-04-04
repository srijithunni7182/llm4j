package io.github.llm4j.model;

/**
 * An optional hint about the complexity of the task requested.
 * This can be used by routing strategies to send simple tasks to cheaper/faster models,
 * and complex tasks to premium/reasoning models.
 */
public enum ComplexityHint {
    /** Simple tasks like summarization, translation, or basic Q&amp;A. */
    LOW,
    
    /** Standard tasks requiring average reasoning. */
    MEDIUM,
    
    /** Complex tasks like coding, extended reasoning, math, or multi-step analysis. */
    HIGH
}
