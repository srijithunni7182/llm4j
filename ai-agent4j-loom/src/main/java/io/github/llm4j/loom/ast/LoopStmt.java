package io.github.llm4j.loom.ast;

import java.util.List;

public class LoopStmt implements Statement {
    private final String condition;
    private final List<Statement> body;

    public LoopStmt(String condition, List<Statement> body) {
        this.condition = condition;
        this.body = body;
    }

    public String getCondition() { return condition; }
    public List<Statement> getBody() { return body; }
}
