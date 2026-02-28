package io.github.llm4j.agent.tool;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import io.github.llm4j.agent.memory.SemanticMemoryService;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class MemoryManagementToolTest {

    @Mock
    private SemanticMemoryService memoryService;

    private MemoryManagementTool tool;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        tool = new MemoryManagementTool(memoryService);
    }

    @Test
    void testGetName() {
        assertEquals("save_memory_fact", tool.getName());
    }

    @Test
    void testExecute_validFact() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("fact", "The user prefers dark mode in all applications.");

        String result = tool.execute(args);

        verify(memoryService).saveFact("The user prefers dark mode in all applications.");
        assertTrue(result.contains("Successfully saved fact"));
    }

    @Test
    void testExecute_missingFact() throws Exception {
        Map<String, Object> args = new HashMap<>();

        String result = tool.execute(args);
        assertEquals("Error: 'fact' argument is missing or empty.", result);
        verifyNoInteractions(memoryService);
    }

    @Test
    void testExecute_serviceThrowsException() throws Exception {
        doThrow(new RuntimeException("DB down")).when(memoryService).saveFact(anyString());

        Map<String, Object> args = new HashMap<>();
        args.put("fact", "Some fact");

        String result = tool.execute(args);
        assertTrue(result.contains("Warning: Failed to save fact due to system error"));
    }
}
