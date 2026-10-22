package io.github.llm4j.grounding;

/**
 * How recent a source must be, for a search that supports it: published within the last day, week,
 * month or year.
 */
public enum Recency {
    DAY,
    WEEK,
    MONTH,
    YEAR
}
