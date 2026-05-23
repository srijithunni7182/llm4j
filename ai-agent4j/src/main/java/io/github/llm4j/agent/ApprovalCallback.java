package io.github.llm4j.agent;

import java.util.Map;

/**
 * Callback interface for Human-in-the-Loop (HITL) approval of sensitive tool executions.
 *
 * <p>Register an implementation via {@link ReActAgent.Builder#approvalCallback(ApprovalCallback)}
 * to intercept any tool that returns {@code true} from
 * {@link Tool#requiresApproval(Map)}. The agent will block execution
 * until this callback returns.
 *
 * <p>Example — console approval:
 * <pre>{@code
 * ReActAgent agent = ReActAgent.builder()
 *     .llmClient(client)
 *     .addTool(new SendEmailTool())
 *     .approvalCallback((toolName, args, thought) -> {
 *         System.out.println("Agent wants to call: " + toolName);
 *         System.out.println("Thought: " + thought);
 *         System.out.println("Args: " + args);
 *         System.out.print("Approve? (yes/no): ");
 *         return new java.util.Scanner(System.in).nextLine().trim().equalsIgnoreCase("yes");
 *     })
 *     .build();
 * }</pre>
 */
@FunctionalInterface
public interface ApprovalCallback {

    /**
     * Called before a sensitive tool is executed.
     *
     * @param toolName the name of the tool requiring approval
     * @param args     the exact arguments the agent intends to pass
     * @param thought  the agent's reasoning that led to this action
     * @return {@code true} to allow execution, {@code false} to reject it
     */
    boolean approve(String toolName, Map<String, Object> args, String thought);
}
