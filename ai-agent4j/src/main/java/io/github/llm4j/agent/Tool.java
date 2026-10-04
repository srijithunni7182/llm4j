package io.github.llm4j.agent;

/**
 * Interface for tools that can be used by the ReAct agent. Tools are functions that the agent can
 * invoke during its reasoning process.
 */
public interface Tool {

    /**
     * Returns the name of the tool. This name will be used by the agent to identify and invoke the
     * tool.
     *
     * @return the tool name
     */
    String getName();

    /**
     * Returns a description of what the tool does and when to use it. This description is used by
     * the LLM to understand the tool's purpose.
     *
     * @return the tool description
     */
    String getDescription();

    /**
     * Executes the tool with the given input arguments.
     *
     * @param args the input arguments to the tool
     * @return the result of executing the tool
     * @throws Exception if the tool execution fails
     */
    String execute(java.util.Map<String, Object> args) throws Exception;

    /**
     * Whether this tool requires explicit human approval before execution. Override and return
     * {@code true} for sensitive, destructive, or high-stakes operations (e.g. sending emails,
     * modifying files, making payments). Defaults to {@code false} so all existing tools remain
     * unaffected.
     *
     * @param args the arguments the agent intends to pass to the tool
     * @return {@code true} if a human must approve this call before execution
     */
    default boolean requiresApproval(java.util.Map<String, Object> args) {
        return false;
    }

    /**
     * The JSON Schema of this tool's arguments, as offered to a model that supports native tool calling. The default accepts any object, which
     * leaves the model to guess argument names from {@link #getDescription()}; declare the real parameters with
     * {@link io.github.llm4j.model.ToolSchema} for reliable calls.
     *
     * @return a JSON Schema object
     */
    default java.util.Map<String, Object> getParametersSchema() {
        return io.github.llm4j.model.ToolSchema.permissive();
    }
}
