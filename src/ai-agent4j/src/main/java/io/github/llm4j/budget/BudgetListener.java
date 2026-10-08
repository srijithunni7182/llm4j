package io.github.llm4j.budget;

/** Receives {@link BudgetEvent}s. */
@FunctionalInterface
public interface BudgetListener {
    void onBudget(BudgetEvent event);
}
