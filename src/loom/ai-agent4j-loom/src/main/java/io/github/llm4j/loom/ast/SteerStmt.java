package io.github.llm4j.loom.ast;

/**
 * {@code steer handle Agent "text"}: tells one {@code steerable} agent of a started workflow what
 * to do next (D12). The text is literal, written in the script: a placeholder is a load error.
 */
public final class SteerStmt extends LocatedStatement {
    private final HandleRef target;
    private final String agent;
    private final String text;

    public SteerStmt(HandleRef target, String agent, String text) {
        this.target = target;
        this.agent = agent;
        this.text = text;
    }

    public HandleRef getTarget() {
        return target;
    }

    public String getAgent() {
        return agent;
    }

    public String getText() {
        return text;
    }
}
