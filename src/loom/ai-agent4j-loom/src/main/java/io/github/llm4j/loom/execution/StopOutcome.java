package io.github.llm4j.loom.execution;

/** What a workflow does with a step whose agent was stopped (see the design in {@code .kiro/specs/loom-agent-intervention}). */
public enum StopOutcome {
    /** Carry on with what the agent had (or the wrap-up answer), marked as degraded. */
    USE_PARTIAL,
    /** The step produces no value; its {@code on_failure} block, if it has one, runs. */
    SKIP,
    /** The step produces no value and nothing runs for it: its work is no longer wanted. */
    DISCARD,
    /** Run the step again from its beginning, with the steers it has received. */
    RETRY,
    /** End the run in a resumable state; the stopped step restarts when it is resumed. */
    SUSPEND,
    /** Bring the whole run to an end. */
    CONCLUDE,
    /** Send the run back to an earlier checkpoint the script declared, carrying the reason; the step runs again after the steps before it. */
    REWIND,
    /** Put the decision to a person. */
    ASK
}
