package io.github.llm4j.agent.tool;

import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.Message;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DelegateTaskToolTest {

    private ToolRegistry toolRegistry;
    private DelegateTaskTool delegateTaskTool;

    @BeforeEach
    void setup() {
        toolRegistry = new ToolRegistry();
        // Register a mock calculator tool that the sub-agent will supposedly use
        toolRegistry.register(new MockCalculatorTool());

        // This mock client intercepts the sub-agent's loop and immediately returns a final answer
        LLMClient mockSubAgentClient =
                new LLMClient() {
                    @Override
                    public LLMResponse chat(LLMRequest request) {
                        // Verify the system prompt got injected correctly
                        Message systemMsg = request.getMessages().get(0);
                        assertEquals(Message.Role.SYSTEM, systemMsg.getRole());
                        assertTrue(systemMsg.getContent().contains("Expert Mathematician"));

                        return LLMResponse.builder().content("I calculated 5 + 5 = 10").build();
                    }

                    @Override
                    public Stream<LLMResponse> chatStream(LLMRequest request) {
                        return Stream.empty();
                    }
                };

        delegateTaskTool = new DelegateTaskTool(toolRegistry, mockSubAgentClient);
    }

    @Test
    void testDelegateTask_successfulExecution() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("role", "Expert Mathematician");
        args.put("instructions", "Calculate 5 + 5");
        args.put("requiredTools", List.of("mock_calculator"));

        String result = delegateTaskTool.execute(args);

        assertTrue(result.contains("Task completed by sub-agent (Expert Mathematician)"));
        assertTrue(result.contains("I calculated 5 + 5 = 10"));
    }

    @Test
    void testDelegateTask_missingArguments() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("instructions", "Calculate 5 + 5"); // Missing role

        String result = delegateTaskTool.execute(args);
        assertTrue(result.contains("Error: Missing or empty 'role' argument"));
    }

    // ---- depth and grants ----

    /** A client that plays the replies in order and keeps what each call was sent. */
    private static final class Scripted implements LLMClient {
        final List<String> sent = new ArrayList<>();
        private final String[] replies;

        Scripted(String... replies) {
            this.replies = replies;
        }

        @Override
        public LLMResponse chat(LLMRequest request) {
            StringBuilder all = new StringBuilder();
            request.getMessages().forEach(m -> all.append(m.getContent()).append('\n'));
            sent.add(all.toString());
            return LLMResponse.builder()
                    .content(replies[Math.min(sent.size() - 1, replies.length - 1)])
                    .build();
        }

        @Override
        public Stream<LLMResponse> chatStream(LLMRequest request) {
            return Stream.empty();
        }
    }

    private static final String DELEGATE_AGAIN =
            "Thought: hand it on.\nAction: delegate_task\n"
                    + "Action Input: {\"role\":\"Sub\",\"instructions\":\"do x\",\"requiredTools\":[\"delegate_task\"]}\n";
    private static final String DELEGATE_PLAIN =
            "Thought: hand it on.\nAction: delegate_task\n"
                    + "Action Input: {\"role\":\"Sub\",\"instructions\":\"do x\",\"requiredTools\":[]}\n";
    private static final String DONE = "Thought: ok.\nFinal Answer: finished";

    private static Map<String, Object> ask(String... tools) {
        Map<String, Object> args = new HashMap<>();
        args.put("role", "Helper");
        args.put("instructions", "do the thing");
        args.put("requiredTools", List.of(tools));
        return args;
    }

    @Test
    void aHelperCanHaveHelpersButTheThirdGenerationCannotDelegateFurther() throws Exception {
        Scripted client = new Scripted(DELEGATE_AGAIN, DONE);
        DelegateTaskTool tool = new DelegateTaskTool(new ToolRegistry(), client); // default depth 2

        String result = tool.execute(ask("delegate_task"));

        assertTrue(result.contains("Task completed by sub-agent (Helper)"));
        assertEquals(2, client.sent.size(), "the refused hand-on started no third agent");
        assertTrue(
                client.sent.get(1).contains("maximum delegation depth 2"),
                "and was told why its request failed: [" + client.sent.get(1) + "]");
    }

    @Test
    void aSecondGenerationHelperRunsWhenItAsksForNoFurtherDelegation() throws Exception {
        Scripted client = new Scripted(DELEGATE_PLAIN, DONE, DONE);
        DelegateTaskTool tool = new DelegateTaskTool(new ToolRegistry(), client);

        String result = tool.execute(ask("delegate_task"));

        assertTrue(result.contains("Task completed"));
        assertEquals(
                3,
                client.sent.size(),
                "manager's helper, that helper's helper, then the first helper's answer");
    }

    @Test
    void depthOneGivesHelpersNoDelegationAndZeroTurnsItOff() throws Exception {
        Scripted none = new Scripted(DONE);
        DelegateTaskTool one =
                DelegateTaskTool.builder(new ToolRegistry(), none).maxDepth(1).build();
        String refused = one.execute(ask("delegate_task"));
        assertTrue(refused.startsWith("Error: Not available to a sub-agent"), refused);
        assertTrue(refused.contains("maximum delegation depth 1"), refused);
        assertEquals(0, none.sent.size(), "no sub-agent was started");

        DelegateTaskTool off =
                DelegateTaskTool.builder(new ToolRegistry(), none).maxDepth(0).build();
        assertTrue(off.execute(ask()).startsWith("Error: Delegation is not allowed here"));
        assertEquals(0, none.sent.size());
    }

    @Test
    void aSubAgentGetsOnlyToolsTheManagerHolds() throws Exception {
        toolRegistry.register(
                new Tool() {
                    public String getName() {
                        return "send_email";
                    }

                    public String getDescription() {
                        return "sends mail";
                    }

                    public String execute(Map<String, Object> args) {
                        return "sent";
                    }
                });
        Scripted client = new Scripted(DONE);
        DelegateTaskTool tool =
                DelegateTaskTool.builder(toolRegistry, client)
                        .grantedTools(List.of("mock_calculator"))
                        .build();

        String refused = tool.execute(ask("mock_calculator", "send_email"));
        assertTrue(
                refused.startsWith("Error: Not available to a sub-agent: [send_email]"), refused);
        assertTrue(
                refused.contains("[mock_calculator]"),
                "it says what can be handed out: " + refused);
        assertEquals(0, client.sent.size(), "nothing was started");

        assertTrue(tool.execute(ask("mock_calculator")).contains("Task completed"));
    }

    @Test
    void delegateTaskItselfMustBeGrantedToo() throws Exception {
        Scripted client = new Scripted(DONE);
        DelegateTaskTool tool =
                DelegateTaskTool.builder(toolRegistry, client)
                        .grantedTools(List.of("mock_calculator"))
                        .build();
        assertTrue(
                tool.execute(ask("delegate_task"))
                        .startsWith("Error: Not available to a sub-agent: [delegate_task]"));
    }

    private static class MockCalculatorTool implements Tool {
        @Override
        public String getName() {
            return "mock_calculator";
        }

        @Override
        public String getDescription() {
            return "Calculates things";
        }

        @Override
        public String execute(Map<String, Object> args) {
            return "10";
        }
    }
}
