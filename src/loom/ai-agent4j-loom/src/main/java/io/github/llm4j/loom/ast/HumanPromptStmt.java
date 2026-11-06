package io.github.llm4j.loom.ast;

public class HumanPromptStmt extends LocatedStatement {
    private final String message;
    private final String variableName;

    public HumanPromptStmt(String message, String variableName) {
        this.message = message;
        this.variableName = variableName;
    }

    private final java.util.List<AttachItem> attachments = new java.util.ArrayList<>();

    /** The files shown to the person with the question; empty for most prompts. */
    public java.util.List<AttachItem> getAttachments() {
        return attachments;
    }

    public String getMessage() {
        return message;
    }

    public String getVariableName() {
        return variableName;
    }
}
