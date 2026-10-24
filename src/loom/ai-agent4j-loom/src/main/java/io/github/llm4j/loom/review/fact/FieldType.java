package io.github.llm4j.loom.review.fact;

/**
 * The kinds of value a fact can have. A rule's operators are checked against these when it loads.
 */
public enum FieldType {
    BOOL,
    NUMBER,
    STRING,
    /** One of a fixed set of words, listed in the field's definition. */
    ENUM,
    /** A list of words. */
    LIST,
    /** A reference to one entity of another kind. */
    ENTITY,
    /** A list of entities of another kind. */
    ENTITY_LIST;

    public boolean scalar() {
        return this == BOOL || this == NUMBER || this == STRING || this == ENUM;
    }

    public boolean list() {
        return this == LIST || this == ENTITY_LIST;
    }
}
