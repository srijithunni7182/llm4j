package io.github.llm4j.loom.ast;

public class DelegateStmt implements Statement {
    private final String payload;
    private final String targetAgent;
    private final String variableName;

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
}
