package io.github.llm4j.loom.ast;

import java.util.List;

public class BroadcastStmt extends LocatedStatement {
    private final String payload;
    private final List<String> targetAgents;
    private final String variableName;

    public BroadcastStmt(String payload, List<String> targetAgents, String variableName) {
        this.payload = payload;
        this.targetAgents = targetAgents;
        this.variableName = variableName;
    }

    public String getPayload() { return payload; }
    public List<String> getTargetAgents() { return targetAgents; }
    public String getVariableName() { return variableName; }

    private BudgetDef budget;
    /** This statement's own budget ({@code budget N tokens}), or null. */
    public BudgetDef getBudget() { return budget; }
    public void setBudget(BudgetDef budget) { this.budget = budget; }
}
