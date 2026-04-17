package io.github.llm4j.loom.ast;

/**
 * AST node for an observation statement.
 * Logs and audits a specific variable or expression value at runtime.
 */
public class ObserveStmt implements Statement {
    private final String label;
    private final String expression;

    public ObserveStmt(String label, String expression) {
        this.label = label;
        this.expression = expression;
    }

    public String getLabel() { return label; }
    public String getExpression() { return expression; }
}
