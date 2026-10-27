package io.github.llm4j.loom.review.rule;

/**
 * The result of a condition: true, false, or unknown when a fact it reads cannot be known. A rule
 * fires only on true.
 */
public enum Tri {
    TRUE,
    FALSE,
    UNKNOWN;

    public static Tri of(boolean b) {
        return b ? TRUE : FALSE;
    }

    public Tri not() {
        return this == TRUE ? FALSE : this == FALSE ? TRUE : UNKNOWN;
    }
}
