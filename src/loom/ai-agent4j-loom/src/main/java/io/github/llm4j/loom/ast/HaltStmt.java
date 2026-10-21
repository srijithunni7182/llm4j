package io.github.llm4j.loom.ast;

/**
 * {@code halt "reason"}: ends the whole run on purpose, saying why. Not a failure: nothing retries
 * it, handles it or rewinds past it.
 */
public class HaltStmt extends LocatedStatement {
    private final String message;

    public HaltStmt(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}
