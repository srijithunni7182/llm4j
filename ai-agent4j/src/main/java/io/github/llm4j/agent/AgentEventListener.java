package io.github.llm4j.agent;

/** Listener interface for agent execution events. */
public interface AgentEventListener {

    /**
     * Called when the agent generates a thought.
     *
     * @param thought The thought text.
     */
    void onThought(String thought);

    /**
     * Called when the agent decides to take an action.
     *
     * @param toolName The name of the tool to execute.
     * @param toolInput The input arguments for the tool.
     */
    void onAction(String toolName, String toolInput);

    /**
     * Called when the tool returns an observation.
     *
     * @param observation The result of the tool execution.
     */
    void onObservation(String observation);
}
