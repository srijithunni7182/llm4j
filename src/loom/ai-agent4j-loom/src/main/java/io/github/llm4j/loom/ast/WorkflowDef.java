package io.github.llm4j.loom.ast;

import java.util.ArrayList;
import java.util.List;

public class WorkflowDef implements Node {
    private final String name;
    private final List<String> parameters = new ArrayList<>();
    private final List<Statement> statements = new ArrayList<>();
    private int line;

    public WorkflowDef(String name) {
        this.name = name;
    }

    /** The 1-based line of the {@code workflow} name, or 0 when unknown. */
    public int getLine() {
        return line;
    }

    public void setLine(int line) {
        this.line = line;
    }

    public String getName() {
        return name;
    }

    public List<String> getParameters() {
        return parameters;
    }

    public void addParameter(String parameter) {
        this.parameters.add(parameter);
    }

    public List<Statement> getStatements() {
        return statements;
    }

    public void addStatement(Statement statement) {
        this.statements.add(statement);
    }
}
