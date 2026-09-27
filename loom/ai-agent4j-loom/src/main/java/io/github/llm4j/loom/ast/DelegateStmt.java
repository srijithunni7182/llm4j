package io.github.llm4j.loom.ast;

import java.util.ArrayList;
import java.util.List;

public class DelegateStmt implements Statement {
    private final String payload;
    private final String targetAgent;
    private final String variableName;
    private int retryCount = 0;
    /** Wait before each retry, doubling every attempt ({@code backoff 2s}); 0 = retry at once. */
    private long backoffMillis;
    /** Give up an attempt after this long ({@code timeout 90s}); 0 = no limit. */
    private long timeoutMillis;
    /** Optional per-step schema ({@code expecting { ... }}) that overrides the agent's output_schema. */
    private SchemaDef expecting;
    private final List<Statement> onFailure = new ArrayList<>();

    public DelegateStmt(String payload, String targetAgent, String variableName) {
        this.payload = payload;
        this.targetAgent = targetAgent;
        this.variableName = variableName;
    }

    public String getPayload() {
        return payload;
    }

    public String getTargetAgent() {
        return targetAgent;
    }

    public String getVariableName() {
        return variableName;
    }

    public long getBackoffMillis() { return backoffMillis; }
    public void setBackoffMillis(long backoffMillis) { this.backoffMillis = backoffMillis; }
    public long getTimeoutMillis() { return timeoutMillis; }
    public void setTimeoutMillis(long timeoutMillis) { this.timeoutMillis = timeoutMillis; }

    /** A target written as {@code {item.owner}} is chosen at run time. */
    public boolean hasDynamicTarget() { return targetAgent.startsWith("{"); }
    public boolean hasDynamicVariable() { return variableName.startsWith("{"); }

    public SchemaDef getExpecting() { return expecting; }
    public void setExpecting(SchemaDef expecting) { this.expecting = expecting; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public List<Statement> getOnFailure() { return onFailure; }

    private BudgetDef budget;
    /** This statement's own budget ({@code budget N tokens}), or null. */
    public BudgetDef getBudget() { return budget; }
    public void setBudget(BudgetDef budget) { this.budget = budget; }
}
