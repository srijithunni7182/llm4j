package io.github.llm4j.loom.runtime;

import java.util.Objects;

/**
 * What a {@code start} statement binds: a reference to a child run (decision D2). It is a
 * capability held by the script, so it is opaque: it prints as a fixed word, never its run id, and
 * the only way to read it is through its facts.
 */
public final class ChildHandle {

    private final String runId;
    private final String workflow;
    private volatile ChildStatus status = ChildStatus.RUNNING;
    private volatile Object result;
    private volatile String reason;
    private volatile String failure;
    private volatile java.time.Instant wakes;

    /**
     * A handle for the child run {@code runId}, started from {@code workflow}.
     *
     * @param runId the child's deterministic run id
     * @param workflow the workflow it runs
     */
    public ChildHandle(String runId, String workflow) {
        this.runId = Objects.requireNonNull(runId, "runId");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
    }

    /** The child's run id, for the host and the journal; a script never sees it. */
    public String runId() {
        return runId;
    }

    /** The workflow the child runs. */
    public String workflow() {
        return workflow;
    }

    /** Where the child is now. */
    public ChildStatus status() {
        return status;
    }

    /** The child ended with this result. */
    public void complete(Object value) {
        this.result = value;
        this.status = ChildStatus.COMPLETED;
    }

    /** The child ended with this status and, for a failure or a stop, the reason. */
    public void end(ChildStatus ended, String why) {
        if (!ended.isSettled() || ended == ChildStatus.COMPLETED)
            throw new IllegalArgumentException(ended + " is not a way a child fails to complete");
        this.reason = ended == ChildStatus.FAILED ? null : why;
        this.failure = ended == ChildStatus.FAILED ? why : null;
        this.status = ended;
    }

    /**
     * The child is asleep (it wakes at {@code wakeAt}) or waiting for a person ({@code wakeAt}
     * null), not running.
     */
    public void pause(ChildStatus now, java.time.Instant wakeAt) {
        if (now != ChildStatus.SLEEPING && now != ChildStatus.WAITING)
            throw new IllegalArgumentException(now + " is not a pause");
        if (!status.isSettled()) {
            this.status = now;
            this.wakes = wakeAt;
        }
    }

    /** When a sleeping child wakes, or null. */
    public java.time.Instant wakes() {
        return wakes;
    }

    /** The child is running again after a pause. */
    public void resume() {
        if (!status.isSettled()) this.status = ChildStatus.RUNNING;
    }

    /** What a settled child produced, or null. Read it through {@link #fact} so the status is checked. */
    Object resultOrNull() {
        return result;
    }

    /**
     * A fact a script may read: {@code status}, {@code result}, {@code reason} or {@code failure}.
     * The result exists only for a completed child: reading it otherwise is an error that names the
     * status (D9).
     *
     * @throws IllegalStateException for the result of a child that did not complete, or a fact this
     *     build does not have yet
     */
    public Object fact(String name) {
        return switch (name) {
            case "status" -> status.word();
            case "result" -> {
                if (status != ChildStatus.COMPLETED)
                    throw new IllegalStateException(
                            "the result of the child started from "
                                    + workflow
                                    + " is not available: its status is "
                                    + status.word()
                                    + ". Check it first: alt (handle is ok) { ... }");
                yield result;
            }
            case "wakes" -> wakes == null ? null : wakes.toString();
            case "reason" -> reason;
            case "failure" -> failure;
            default -> throw new IllegalStateException(
                    "the fact \"" + name + "\" of a started workflow is not available in this build");
        };
    }

    /** True only when the child completed (D9): anything else, including not yet, is not ok. */
    public boolean isOk() {
        return status == ChildStatus.COMPLETED;
    }

    @Override
    public String toString() {
        return "[handle to " + workflow + "]";
    }
}
