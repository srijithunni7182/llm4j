package io.github.llm4j.agent.tool;

import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.Message;
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
        LLMClient mockSubAgentClient = new LLMClient() {
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

    private static class MockCalculatorTool implements Tool {
        @Override
        public String getName() { return "mock_calculator"; }

        @Override
        public String getDescription() { return "Calculates things"; }

        @Override
        public String execute(Map<String, Object> args) { return "10"; }
    }
}
