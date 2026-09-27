package io.github.llm4j.budget;

/** What an agent does when its budget runs out mid-task. */
public enum BudgetPolicy {
    /** Return the best answer so far, marked as budget-exhausted (the default). */
    RETURN_PARTIAL,
    /** Propagate {@link BudgetExceeded}. */
    FAIL
}
