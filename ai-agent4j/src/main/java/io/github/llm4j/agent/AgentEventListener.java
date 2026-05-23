package io.github.llm4j.agent;

import java.util.Map;

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

    /**
     * Called when a tool requires human approval before execution. Listeners can use this to
     * surface a UI prompt, send a notification, or log a pending approval request.
     *
     * <p>This is a notification-only hook; the approval decision itself is made via the
     * {@link ApprovalCallback} registered on the agent builder.
     *
     * @param toolName The name of the tool awaiting approval.
     * @param args The arguments the agent intends to pass.
     * @param thought The agent's reasoning for wanting to call this tool.
     */
    default void onApprovalRequired(String toolName, Map<String, Object> args, String thought) {
        // no-op by default; implementors opt in
    }
}
