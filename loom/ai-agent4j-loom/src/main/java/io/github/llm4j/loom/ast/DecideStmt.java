package io.github.llm4j.loom.ast;

/** {@code decide Refund -> verdict}: one case of a declared decision. Binds {@code verdict}, {@code verdict_proposal} and {@code verdict_level}. */
public class DecideStmt implements Statement {
    private final String decision;
    private final String variable;
    private int line;

    public DecideStmt(String decision, String variable) {
        this.decision = decision;
        this.variable = variable;
    }

    public String getDecision() { return decision; }
    public String getVariable() { return variable; }
    public int getLine() { return line; }
    public void setLine(int line) { this.line = line; }
}
