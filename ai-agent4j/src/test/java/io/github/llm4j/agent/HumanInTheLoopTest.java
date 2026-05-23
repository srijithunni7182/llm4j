package io.github.llm4j.agent;

import io.github.llm4j.LLMClient;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Human-in-the-Loop (HITL) approval gate in {@link ReActAgent}.
 */
class HumanInTheLoopTest {

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** A mock LLM that returns one action call then a final answer. */
    private static LLMClient llmThatCalls(String toolName, String toolArgs) {
        AtomicInteger turn = new AtomicInteger(0);
        return new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                if (turn.getAndIncrement() == 0) {
                    return LLMResponse.builder().content("""
                            ```json
                            {
                              "thought": "I should call the sensitive tool.",
                              "action": "%s",
                              "action_input": %s
                            }
                            ```
                            """.formatted(toolName, toolArgs)).build();
                }
                return LLMResponse.builder().content("""
                        ```json
                        {"thought": "Done.", "final_answer": "Task complete."}
                        ```
                        """).build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.empty();
            }
        };
    }

    /** A sensitive tool that records whether it was actually executed. */
    private static Tool sensitiveTool(AtomicBoolean executed) {
        return new Tool() {
            @Override public String getName() { return "SendEmail"; }
            @Override public String getDescription() { return "Sends an email."; }

            @Override
            public boolean requiresApproval(Map<String, Object> args) {
                return true; // always requires approval
            }

            @Override
            public String execute(Map<String, Object> args) {
                executed.set(true);
                return "Email sent.";
            }
        };
    }

    // ── Tests ──────────────────────────────────────────────────────────────────

    @Test
    void toolIsExecutedWhenApproved() {
        AtomicBoolean executed = new AtomicBoolean(false);

        ReActAgent agent = ReActAgent.builder()
                .llmClient(llmThatCalls("SendEmail", "{\"to\":\"boss@example.com\"}"))
                .addTool(sensitiveTool(executed))
                .approvalCallback((tool, args, thought) -> true) // always approve
                .maxIterations(3)
                .build();

        AgentResult result = agent.run("Send an email.");
        assertTrue(executed.get(), "Tool should have been executed after approval");
    }

    @Test
    void toolIsBlockedWhenRejected() {
        AtomicBoolean executed = new AtomicBoolean(false);

        ReActAgent agent = ReActAgent.builder()
                .llmClient(llmThatCalls("SendEmail", "{\"to\":\"boss@example.com\"}"))
                .addTool(sensitiveTool(executed))
                .approvalCallback((tool, args, thought) -> false) // always reject
                .maxIterations(3)
                .build();

        AgentResult result = agent.run("Send an email.");
        assertFalse(executed.get(), "Tool should NOT have been executed after rejection");
    }

    @Test
    void missingApprovalCallbackBlocksExecution() {
        AtomicBoolean executed = new AtomicBoolean(false);

        ReActAgent agent = ReActAgent.builder()
                .llmClient(llmThatCalls("SendEmail", "{\"to\":\"boss@example.com\"}"))
                .addTool(sensitiveTool(executed))
                // intentionally no approvalCallback set
                .maxIterations(3)
                .build();

        agent.run("Send an email.");
        assertFalse(executed.get(), "Tool should be blocked when no ApprovalCallback is configured");
    }

    @Test
    void approvalCallbackReceivesCorrectContext() {
        AtomicBoolean callbackInvoked = new AtomicBoolean(false);

        ReActAgent agent = ReActAgent.builder()
                .llmClient(llmThatCalls("SendEmail", "{\"to\":\"boss@example.com\"}"))
                .addTool(sensitiveTool(new AtomicBoolean()))
                .approvalCallback((toolName, args, thought) -> {
                    assertEquals("SendEmail", toolName);
                    assertEquals("boss@example.com", args.get("to"));
                    assertNotNull(thought);
                    callbackInvoked.set(true);
                    return false;
                })
                .maxIterations(3)
                .build();

        agent.run("Send an email.");
        assertTrue(callbackInvoked.get(), "ApprovalCallback should have been invoked");
    }

    @Test
    void listenerIsNotifiedOnApprovalRequired() {
        AtomicBoolean notified = new AtomicBoolean(false);

        AgentEventListener listener = new AgentEventListener() {
            @Override public void onThought(String thought) {}
            @Override public void onAction(String toolName, String toolInput) {}
            @Override public void onObservation(String observation) {}

            @Override
            public void onApprovalRequired(String toolName, Map<String, Object> args, String thought) {
                notified.set(true);
            }
        };

        ReActAgent agent = ReActAgent.builder()
                .llmClient(llmThatCalls("SendEmail", "{\"to\":\"boss@example.com\"}"))
                .addTool(sensitiveTool(new AtomicBoolean()))
                .addListener(listener)
                .approvalCallback((tool, args, thought) -> false)
                .maxIterations(3)
                .build();

        agent.run("Send an email.");
        assertTrue(notified.get(), "Listener should have been notified of pending approval");
    }
}
