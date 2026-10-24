package io.github.llm4j.loom.review.model;

/**
 * Something the review says about itself rather than about the workflow: a rule that was not
 * evaluated because a fact is undeclared, a fix that was withheld, a source that failed. A silent
 * lens must never be mistaken for a clean one, so these are always listed.
 */
public record Note(Kind kind, String rule, String message) {

    /** The kinds of note. */
    public enum Kind {
        NOT_EVALUATED,
        FIX_WITHHELD,
        SOURCE_FAILED,
        INFO
    }

    public static Note notEvaluated(String rule, String message) {
        return new Note(Kind.NOT_EVALUATED, rule, message);
    }
}
