package io.github.llm4j.loom.ast;

public class HumanPromptStmt extends LocatedStatement {
    private final String message;
    private final String variableName;

    public HumanPromptStmt(String message, String variableName) {
        this.message = message;
        this.variableName = variableName;
    }

    public String getMessage() { return message; }
    public String getVariableName() { return variableName; }
}
