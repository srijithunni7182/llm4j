package io.github.llm4j.loom.runtime;

import java.util.Map;

/** The standard interface for a Loom execution engine. */
public interface LoomEngine {
    /** Performs any initial setup (e.g., booting MCP servers, building agents, etc.). */
    void initialize();

    /**
     * Executes a specific workflow.
     *
     * @param workflowName the name of the workflow to run
     * @param initialContext initial variable values
     */
    void executeWorkflow(String workflowName, Map<String, String> initialContext);

    /**
     * Executes a workflow for a person: tools whose scripts say {@code invoker.NAME} act with this
     * person's credentials, asked for on every request. An engine that has no such tools may ignore
     * them, which is what the default does.
     */
    default void executeWorkflow(
            String workflowName,
            Map<String, String> initialContext,
            io.github.llm4j.secret.InvokerCredentials invoker) {
        executeWorkflow(workflowName, initialContext);
    }

    /** Releases resources used by the engine. */
    void shutdown();

    /** Returns the current variable context. */
    VariableContext getContext();
}
