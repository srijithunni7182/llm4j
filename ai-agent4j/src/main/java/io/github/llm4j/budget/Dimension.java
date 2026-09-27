package io.github.llm4j.budget;

/** What a budget limits. */
public enum Dimension {
    /** Prompt plus completion tokens. */
    TOKENS,
    /** Number of LLM calls. */
    CALLS,
    /** Money, computed from a {@link PriceTable}. */
    COST
}
