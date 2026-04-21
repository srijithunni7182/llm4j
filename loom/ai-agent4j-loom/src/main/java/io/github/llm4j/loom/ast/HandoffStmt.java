package io.github.llm4j.loom.ast;

public class HandoffStmt implements Statement {
    private final String payload;
    private final String targetAgent;

    public HandoffStmt(String payload, String targetAgent) {
        this.payload = payload;
        this.targetAgent = targetAgent;
    }

    public String getPayload() {
        return payload;
    }

    public String getTargetAgent() {
        return targetAgent;
    }
}
