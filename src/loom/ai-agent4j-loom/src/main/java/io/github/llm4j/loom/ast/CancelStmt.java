package io.github.llm4j.loom.ast;

/**
 * {@code cancel handle [outcome] [reason "..."]}: stops a child with one of the existing outcomes
 * (D13). {@code cancel h rewind to <checkpoint> at most N times} sends it back to a checkpoint the
 * child declared and lets it go on.
 */
public final class CancelStmt extends LocatedStatement {
    private final HandleRef target;
    private OutcomeStmt.Kind outcome;
    private String reason;
    private String rewindCheckpoint;
    private Integer rewindAtMost;

    public CancelStmt(HandleRef target) {
        this.target = target;
    }

    public HandleRef getTarget() {
        return target;
    }

    /** The outcome, or null for the default. */
    public OutcomeStmt.Kind getOutcome() {
        return outcome;
    }

    public void setOutcome(OutcomeStmt.Kind outcome) {
        this.outcome = outcome;
    }

    /** Why, as written, or null. */
    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    /** The checkpoint to send the child back to, or null when this is not a rewind. */
    public String getRewindCheckpoint() {
        return rewindCheckpoint;
    }

    /** How many times the child may be sent back, or null when this is not a rewind. */
    public Integer getRewindAtMost() {
        return rewindAtMost;
    }

    public void setRewind(String checkpoint, int atMost) {
        this.rewindCheckpoint = checkpoint;
        this.rewindAtMost = atMost;
    }
}
