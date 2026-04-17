package io.github.llm4j.loom.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * AST node for a parallel execution block.
 * Each statement within the body is executed concurrently.
 */
public class ParallelStmt implements Statement {
    private final List<Statement> body = new ArrayList<>();

    public List<Statement> getBody() { return body; }
    public void addStatement(Statement stmt) { this.body.add(stmt); }
}
