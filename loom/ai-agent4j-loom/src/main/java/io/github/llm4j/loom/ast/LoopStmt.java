package io.github.llm4j.loom.ast;

import java.util.List;

public class LoopStmt implements Statement {
    private final String condition;
    private final List<Statement> body;
    /** Upper bound on iterations ({@code max N}); 0 = unbounded (the original behaviour). */
    private int maxIterations;
    /** Runs once if the loop stops because it hit {@code max} before its condition became true. */
    private final List<Statement> onExhausted = new java.util.ArrayList<>();

    public LoopStmt(String condition, List<Statement> body) {
        this.condition = condition;
        this.body = body;
    }

    public String getCondition() { return condition; }
    public List<Statement> getBody() { return body; }
    public int getMaxIterations() { return maxIterations; }
    public void setMaxIterations(int maxIterations) { this.maxIterations = maxIterations; }
    public List<Statement> getOnExhausted() { return onExhausted; }

    private BudgetDef budget;
    /** This statement's own budget ({@code budget N tokens}), or null. */
    public BudgetDef getBudget() { return budget; }
    public void setBudget(BudgetDef budget) { this.budget = budget; }
}
