package io.github.llm4j.loom.ast;

public class NoteStmt extends LocatedStatement {
    private final String message;

    public NoteStmt(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}
