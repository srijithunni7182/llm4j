package io.github.loom.ctk;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ConformanceResult data model.
 */
class ConformanceResultTest {
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Test
    void testConformanceResultPassed() {
        ConformanceResult result = new ConformanceResult(
            "test_delegate_basic",
            true,
            List.of()
        );
        
        assertEquals("test_delegate_basic", result.testName());
        assertTrue(result.passed());
        assertTrue(result.differences().isEmpty());
    }
    
    @Test
    void testConformanceResultFailed() {
        ConformanceResult result = new ConformanceResult(
            "test_handoff",
            false,
            List.of(
                "workflowName mismatch: actualWorkflow vs expectedWorkflow",
                "[step 0] kind: delegate vs handoff"
            )
        );
        
        assertEquals("test_handoff", result.testName());
        assertFalse(result.passed());
        assertEquals(2, result.differences().size());
        assertTrue(result.differences().get(0).contains("workflowName mismatch"));
        assertTrue(result.differences().get(1).contains("[step 0]"));
    }
    
    @Test
    void testConformanceResultJsonSerialization() throws Exception {
        ConformanceResult result = new ConformanceResult(
            "test_broadcast",
            false,
            List.of("step count mismatch: 3 vs 2")
        );
        
        // Serialize to JSON
        String json = objectMapper.writeValueAsString(result);
        
        // Verify JSON contains expected fields
        assertTrue(json.contains("\"testName\":\"test_broadcast\""));
        assertTrue(json.contains("\"passed\":false"));
        assertTrue(json.contains("\"differences\""));
        assertTrue(json.contains("step count mismatch"));
        
        // Deserialize back
        ConformanceResult deserialized = objectMapper.readValue(json, ConformanceResult.class);
        
        assertEquals(result.testName(), deserialized.testName());
        assertEquals(result.passed(), deserialized.passed());
        assertEquals(result.differences().size(), deserialized.differences().size());
        assertEquals(result.differences().get(0), deserialized.differences().get(0));
    }
}
