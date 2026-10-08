package io.github.llm4j.loom.runtime;

import io.github.llm4j.loom.ast.LoomScript;
import java.util.Map;

/**
 * The standard interface for a Loom execution engine.
 */
public interface LoomEngine {
    /**
     * Performs any initial setup (e.g., booting MCP servers, building agents, etc.).
     */
    void initialize();

    /**
     * Executes a specific workflow.
     * 
     * @param workflowName   the name of the workflow to run
     * @param initialContext initial variable values
     */
    void executeWorkflow(String workflowName, Map<String, String> initialContext);

    /**
     * Releases resources used by the engine.
     */
    void shutdown();

    /**
     * Returns the current variable context.
     */
    VariableContext getContext();
}
