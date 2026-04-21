package io.github.llm4j.loom.ast;

import java.util.ArrayList;
import java.util.List;

public class DelegateStmt implements Statement {
    private final String payload;
    private final String targetAgent;
    private final String variableName;
    private int retryCount = 0;
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

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public List<Statement> getOnFailure() { return onFailure; }
}
