package io.github.llm4j.loom.ast;

import java.util.ArrayList;
import java.util.List;

public class WorkflowDef implements Node {
    private final String name;
    private final List<String> parameters = new ArrayList<>();
    private final List<Statement> statements = new ArrayList<>();

    public WorkflowDef(String name) {
        this.name = name;
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
