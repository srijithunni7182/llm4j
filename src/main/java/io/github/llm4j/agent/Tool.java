package io.github.llm4j.agent;

/**
 * Interface for tools that can be used by the ReAct agent.
 * Tools are functions that the agent can invoke during its reasoning process.
 */
public interface Tool {

    /**
     * Returns the name of the tool. This name will be used by the agent
     * to identify and invoke the tool.
     *
     * @return the tool name
     */
    String getName();

    /**
     * Returns a description of what the tool does and when to use it.
     * This description is used by the LLM to understand the tool's purpose.
     *
     * @return the tool description
     */
    String getDescription();

    /**
     * Executes the tool with the given input.
     *
     * @param input the input to the tool
     * @return the result of executing the tool
     * @throws Exception if the tool execution fails
     */
    String execute(String input) throws Exception;
}
