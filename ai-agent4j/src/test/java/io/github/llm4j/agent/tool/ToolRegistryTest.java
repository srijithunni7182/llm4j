package io.github.llm4j.agent.tool;

import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.agent.Tool;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {

    private ToolRegistry registry;

    @BeforeEach
    void setup() {
        registry = new ToolRegistry();
    }

    @Test
    void testRegisterAndGetTool() {
        Tool mockTool = new MockTool("test_tool");
        registry.register(mockTool);

        Tool retrieved = registry.getTool("test_tool");
        assertNotNull(retrieved);
        assertEquals("test_tool", retrieved.getName());
    }

    @Test
    void testResolveTools() {
        registry.register(new MockTool("tool_a"));
        registry.register(new MockTool("tool_b"));

        List<Tool> resolved = registry.resolveTools(List.of("tool_a", "tool_b", "non_existent"));
        
        assertEquals(2, resolved.size());
        assertTrue(resolved.stream().anyMatch(t -> t.getName().equals("tool_a")));
        assertTrue(resolved.stream().anyMatch(t -> t.getName().equals("tool_b")));
    }

    private static class MockTool implements Tool {
        private final String name;

        MockTool(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "Mock description";
        }

        @Override
        public String execute(Map<String, Object> args) {
            return "executed";
        }
    }
}
