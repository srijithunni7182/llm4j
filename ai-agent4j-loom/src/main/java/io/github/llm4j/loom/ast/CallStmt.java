package io.github.llm4j.loom.ast;

import java.util.Map;

/**
 * Represents a call to a sub-workflow.
 * Form: call WorkflowName(arg1=val1, ...) -> resultVar
 */
public class CallStmt implements Statement {
    private final String workflowName;
    private final Map<String, String> arguments;
    private final String resultVariable;

    public CallStmt(String workflowName, Map<String, String> arguments, String resultVariable) {
        this.workflowName = workflowName;
        this.arguments = arguments;
        this.resultVariable = resultVariable;
    }

    public String getWorkflowName() { return workflowName; }
    public Map<String, String> getArguments() { return arguments; }
    public String getResultVariable() { return resultVariable; }
}
