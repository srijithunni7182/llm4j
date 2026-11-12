package io.github.llm4j.loom.runtime;

/**
 * Where a started workflow is (decision D9). {@code COMPLETED} is the only status with a result;
 * {@code CANCELLED} and {@code TIMED_OUT} stay apart from {@code STOPPED}, which is a person or a
 * rule inside the child.
 */
public enum ChildStatus {
    RUNNING("running", false),
    SLEEPING("sleeping", false),
    WAITING("waiting", false),
    COMPLETED("completed", true),
    FAILED("failed", true),
    STOPPED("stopped", true),
    CANCELLED("cancelled", true),
    TIMED_OUT("timed out", true);

    private final String word;
    private final boolean settled;

    ChildStatus(String word, boolean settled) {
        this.word = word;
        this.settled = settled;
    }

    /** As a script reads it: {@code h.status == "timed out"}. */
    public String word() {
        return word;
    }

    /** True when the child will do nothing more; sleeping and waiting children are not settled. */
    public boolean isSettled() {
        return settled;
    }
}
