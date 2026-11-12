package io.github.llm4j.loom.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code start Workflow(name = value, ...) [timeout 20m] budget { ... } -> handle}: starts a
 * workflow as a child run of its own without waiting for it, and binds a handle. Unlike {@code
 * call}, the parent goes on at once. The deadline is wall clock (D26). The budget is required (D11)
 * but is read as optional here so that {@code weave check} can say what to do.
 */
public final class StartStmt extends LocatedStatement {
    private final String workflow;
    private final List<RunStmt.Arg> args;
    private final HandleRef target;
    private Long timeoutMillis;
    private BudgetDef budget;

    public StartStmt(String workflow, List<RunStmt.Arg> args, HandleRef target) {
        this.workflow = workflow;
        this.args = new ArrayList<>(args);
        this.target = target;
    }

    public String getWorkflow() {
        return workflow;
    }

    public List<RunStmt.Arg> getArgs() {
        return args;
    }

    /** Where the handle is bound. */
    public HandleRef getTarget() {
        return target;
    }

    /** The wall-clock deadline in milliseconds, or null for none. */
    public Long getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(Long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    /** The child's own budget, or null when the script gave none. */
    public BudgetDef getBudget() {
        return budget;
    }

    public void setBudget(BudgetDef budget) {
        this.budget = budget;
    }
}
