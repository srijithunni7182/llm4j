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

    /**
     * Called when the agent's budget crosses its warning threshold, or refuses a call.
     *
     * @param event what happened, with the budget's spend and limits
     */
    default void onBudget(io.github.llm4j.budget.BudgetEvent event) {
        // no-op by default; implementors opt in
    }

    /**
     * Called before each model call with how full the context is. {@link io.github.llm4j.context.ContextUsage#fraction()} is empty
     * when the model's window is not known.
     *
     * @param usage the prompt size, where it came from (the provider's count or an estimate) and the window
     */
    default void onContextUsage(io.github.llm4j.context.ContextUsage usage) {
        // no-op by default; implementors opt in
    }

    /**
     * Called after each attempt to compact the conversation: a compaction, a result that was not used, a strategy that failed, and the one
     * notice that compaction has stopped for the run.
     *
     * @param event what was tried and what came of it
     */
    default void onCompaction(io.github.llm4j.context.CompactionEvent event) {
        // no-op by default; implementors opt in
    }

    /**
     * Called after each call to a model, whether it produced an answer or failed: how long it took, how many attempts it needed, the tokens, the
     * finish reason, and why it failed. The event holds numbers and names only, never the prompt or the answer.
     *
     * @param event what the call was and how it went
     */
    default void onModelCall(io.github.llm4j.telemetry.ModelCallEvent event) {
        // no-op by default; implementors opt in
    }

    /**
     * Called after each tool call, whatever happened to it: it ran, it threw, it returned an error as text, it was blocked, unknown or rejected. The
     * event holds numbers and names only, never the arguments or the result (see {@link #onAction} and {@link #onObservation} for those).
     *
     * @param event what the call was and how it went
     */
    default void onToolCall(io.github.llm4j.telemetry.ToolCallEvent event) {
        // no-op by default; implementors opt in
    }
}
