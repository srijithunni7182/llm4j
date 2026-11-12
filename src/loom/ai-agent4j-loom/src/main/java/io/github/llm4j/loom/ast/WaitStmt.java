package io.github.llm4j.loom.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code wait handle timeout 5m -> result} or {@code wait all handles timeout 25m -> results}:
 * parks until the child (or every child) settles or the timeout passes (D8). The timeout is
 * required, but is read as optional so that {@code weave check} can refuse a wait without one.
 */
public final class WaitStmt extends LocatedStatement {
    private final HandleRef target;
    private final boolean all;
    private Long timeoutMillis;
    private String resultVariable;
    private final List<Statement> onTimeout = new ArrayList<>();
    private final List<Statement> onFailure = new ArrayList<>();

    public WaitStmt(HandleRef target, boolean all) {
        this.target = target;
        this.all = all;
    }

    public HandleRef getTarget() {
        return target;
    }

    /** True for {@code wait all}, which waits for every handle of a keyed variable. */
    public boolean isAll() {
        return all;
    }

    public Long getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(Long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    /** The variable that receives the result (a keyed variable for {@code wait all}), or null. */
    public String getResultVariable() {
        return resultVariable;
    }

    public void setResultVariable(String resultVariable) {
        this.resultVariable = resultVariable;
    }

    public List<Statement> getOnTimeout() {
        return onTimeout;
    }

    public List<Statement> getOnFailure() {
        return onFailure;
    }
}
