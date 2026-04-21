package io.github.llm4j.loom.ast;

public class NoteStmt implements Statement {
    private final String message;

    public NoteStmt(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}
