package io.github.llm4j.loom.ast;

/**
 * {@code wait signal "name" timeout T -> text} (in a child: waits for a signal from its parent) or
 * {@code wait <handle> signal "name" timeout T -> text} (in a parent: waits for one from a child),
 * phase 34c. A timeout is required. The result is the signal's text, always {@code unchecked}; if
 * the timeout passes first it is a value that raises an error when read.
 */
public final class SignalWaitStmt extends LocatedStatement {
    private final HandleRef target;
    private final String name;
    private Long timeoutMillis;
    private String resultVariable;

    /** @param target the child waited on, or null for a child waiting on its parent */
    public SignalWaitStmt(HandleRef target, String name) {
        this.target = target;
        this.name = name;
    }

    /** The child whose signal is awaited, or null in a child waiting for its parent's. */
    public HandleRef getTarget() {
        return target;
    }

    public String getName() {
        return name;
    }

    public Long getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(Long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    public String getResultVariable() {
        return resultVariable;
    }

    public void setResultVariable(String resultVariable) {
        this.resultVariable = resultVariable;
    }
}
