package io.github.llm4j.loom.ast;

import java.util.ArrayList;
import java.util.List;

public class AltStmt implements Statement {
    private final String condition;
    private final List<Statement> ifBranch = new ArrayList<>();
    private final List<Statement> elseBranch = new ArrayList<>();

    public AltStmt(String condition) {
        this.condition = condition;
    }

    public String getCondition() {
        return condition;
    }

    public List<Statement> getIfBranch() {
        return ifBranch;
    }

    public List<Statement> getElseBranch() {
        return elseBranch;
    }

    public void addIfStatement(Statement stmt) {
        this.ifBranch.add(stmt);
    }

    public void addElseStatement(Statement stmt) {
        this.elseBranch.add(stmt);
    }
}
