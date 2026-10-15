package io.github.llm4j.loom.execution;

import java.util.Objects;

/**
 * What the run should do when a person cancels an agent, as an embedding application names it. {@link #byTheScript()} leaves it to the script's
 * {@code on_stopped} and {@code intervention} (and, failing both, to asking a person).
 */
public final class Outcome {

    public static final Outcome USE_PARTIAL = new Outcome(StopOutcome.USE_PARTIAL, null);
    public static final Outcome SKIP = new Outcome(StopOutcome.SKIP, null);
    public static final Outcome DISCARD = new Outcome(StopOutcome.DISCARD, null);
    public static final Outcome RETRY = new Outcome(StopOutcome.RETRY, null);
    public static final Outcome SUSPEND = new Outcome(StopOutcome.SUSPEND, null);
    public static final Outcome CONCLUDE = new Outcome(StopOutcome.CONCLUDE, null);
    public static final Outcome ASK = new Outcome(StopOutcome.ASK, null);
    private static final Outcome SCRIPT = new Outcome(null, null);

    private final StopOutcome outcome;
    private final String checkpoint;

    private Outcome(StopOutcome outcome, String checkpoint) {
        this.outcome = outcome;
        this.checkpoint = checkpoint;
    }

    /** No outcome named: the script decides. */
    public static Outcome byTheScript() {
        return SCRIPT;
    }

    /** Send the run back to a checkpoint the script declared; refused unless the step's {@code on_stopped} has a rewind that allows it. */
    public static Outcome rewindTo(String checkpoint) {
        return new Outcome(StopOutcome.REWIND, Objects.requireNonNull(checkpoint, "checkpoint"));
    }

    StopOutcome outcome() {
        return outcome;
    }

    String checkpoint() {
        return checkpoint;
    }
}
