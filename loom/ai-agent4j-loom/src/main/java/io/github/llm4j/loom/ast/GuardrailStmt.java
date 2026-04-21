package io.github.llm4j.loom.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * AST node for a guardrail statement (e.g., PII protection).
 */
public class GuardrailStmt implements Statement {
    private final String type;
    private final List<Statement> body = new ArrayList<>();
    private final List<Statement> onViolation = new ArrayList<>();

    public GuardrailStmt(String type) {
        this.type = type;
    }

    public String getType() { return type; }
    public List<Statement> getBody() { return body; }
    public void addBodyStatement(Statement stmt) { this.body.add(stmt); }
    public List<Statement> getOnViolation() { return onViolation; }
    public void addViolationStatement(Statement stmt) { this.onViolation.add(stmt); }
}
