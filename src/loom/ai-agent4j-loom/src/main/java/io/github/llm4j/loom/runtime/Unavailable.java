package io.github.llm4j.loom.runtime;

import java.util.Objects;

/**
 * What {@code wait handle -> r} binds to {@code r} when the child did not complete (decision 33-D9,
 * closed in phase 34 as F2). It is a typed absent value that fails closed: comparing or passing it
 * on is harmless, but <em>reading</em> it into text or taking a field of it raises the status
 * error, the same one {@code handle.result} raises, so an empty string is never mistaken for an
 * answer.
 */
public final class Unavailable {

    private final String workflow;
    private final String status;

    private Unavailable(String workflow, String status) {
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.status = Objects.requireNonNull(status, "status");
    }

    /**
     * The absent result of a child started from {@code workflow} that ended as {@code status}.
     *
     * @param workflow the workflow the child ran
     * @param status the child's status word, as {@link ChildStatus#word()} says it
     */
    public static Unavailable of(String workflow, String status) {
        return new Unavailable(workflow, status);
    }

    /** The error a read of this value raises: it names the workflow and the status, and what to do. */
    public String message() {
        return "the result of the child started from "
                + workflow
                + " is not available: its status is "
                + status
                + ". Check it first: alt (handle is ok) { ... }";
    }

    /** A fixed description, never the value of anything: safe in a log or a console. */
    @Override
    public String toString() {
        return "[no result: the child started from " + workflow + " is " + status + "]";
    }
}
