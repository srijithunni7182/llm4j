package io.github.llm4j.loom.review.rule;

import java.util.Arrays;
import java.util.Optional;

/** The operators of a rule's condition: a closed list, with no way to add one from a rule file. */
public enum Op {
    IS("is"),
    IS_NOT("isNot"),
    IN("in"),
    NOT_IN("notIn"),
    GT("gt"),
    GTE("gte"),
    LT("lt"),
    LTE("lte"),
    CONTAINS("contains"),
    STARTS_WITH("startsWith"),
    MATCHES("matches"),
    EXISTS("exists"),
    MISSING("missing"),
    COUNT("count"),
    SOME("some"),
    EVERY("every"),
    NONE("none");

    private final String key;

    Op(String key) {
        this.key = key;
    }

    /** The word used in a rule file. */
    public String key() {
        return key;
    }

    public static Optional<Op> ofKey(String key) {
        return Arrays.stream(values()).filter(o -> o.key.equals(key)).findFirst();
    }

    /** True for the operators that compare a number with a bound. */
    public boolean comparison() {
        return this == GT || this == GTE || this == LT || this == LTE;
    }

    /** True for the operators that take a nested condition over the elements of a list. */
    public boolean quantifier() {
        return this == SOME || this == EVERY || this == NONE;
    }
}
