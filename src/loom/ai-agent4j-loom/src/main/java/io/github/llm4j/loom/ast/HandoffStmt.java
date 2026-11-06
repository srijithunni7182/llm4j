package io.github.llm4j.loom.ast;

public class HandoffStmt extends LocatedStatement {
    private final String payload;
    private final String targetAgent;

    public HandoffStmt(String payload, String targetAgent) {
        this.payload = payload;
        this.targetAgent = targetAgent;
    }

    private final java.util.List<AttachItem> attachments = new java.util.ArrayList<>();

    /** The pictures, sound, video and documents sent with the task; empty for most steps. */
    public java.util.List<AttachItem> getAttachments() {
        return attachments;
    }

    public String getPayload() {
        return payload;
    }

    public String getTargetAgent() {
        return targetAgent;
    }
}
