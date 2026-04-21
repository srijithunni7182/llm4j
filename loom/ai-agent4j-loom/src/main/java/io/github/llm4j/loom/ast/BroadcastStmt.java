package io.github.llm4j.loom.ast;

import java.util.List;

public class BroadcastStmt implements Statement {
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
}
