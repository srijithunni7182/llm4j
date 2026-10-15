package io.github.llm4j.telemetry;

/** What happened to a tool call, as far as the agent can tell. The same cases as the agent's step outcome, so that this package stands alone. */
public enum ToolOutcome {
    /** The tool ran and returned a result (which may still be an error returned as text: see {@link ToolCallEvent#softFailure()}). */
    EXECUTED(null),
    /** The model named a tool that does not exist. */
    UNKNOWN_TOOL(ErrorCategory.UNKNOWN_TOOL),
    /** Blocked as an exact repeat of an earlier action. */
    DUPLICATE_BLOCKED(ErrorCategory.REPEAT_BLOCKED),
    /** The action needs approval and no way to ask was set up. */
    APPROVAL_UNAVAILABLE(ErrorCategory.APPROVAL_UNAVAILABLE),
    /** A person rejected the action. */
    REJECTED_BY_HUMAN(ErrorCategory.REJECTED_BY_HUMAN),
    /** The tool threw. */
    EXECUTION_ERROR(ErrorCategory.OTHER),
    /** The budget ran out. */
    BUDGET_EXHAUSTED(ErrorCategory.BUDGET);

    private final ErrorCategory defaultCategory;

    ToolOutcome(ErrorCategory defaultCategory) {
        this.defaultCategory = defaultCategory;
    }

    /** The category that goes with this outcome when nothing more specific is known; null for {@link #EXECUTED}. */
    public ErrorCategory defaultCategory() {
        return defaultCategory;
    }

    /** Whether the tool's own code was reached. */
    public boolean ran() {
        return this == EXECUTED || this == EXECUTION_ERROR;
    }
}
