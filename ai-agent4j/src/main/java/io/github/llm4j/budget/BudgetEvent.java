package io.github.llm4j.budget;

/** A budget crossed its warning threshold, or refused a call. */
public record BudgetEvent(Kind kind, String budget, Spent spent, Limits limits) {

    public enum Kind {
        /** Spend crossed the warning threshold (fired once per budget). */
        WARNING,
        /** The budget refused a call (fired once per budget). */
        EXHAUSTED
    }
}
