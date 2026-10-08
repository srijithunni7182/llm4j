package io.github.loom.ctk;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ExecutionTrace and TraceStep data models.
 */
class ExecutionTraceTest {
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Test
    void testExecutionTraceCreation() {
        TraceStep step = new TraceStep(
            "delegate",
            "TestAgent",
            "test payload",
            "result",
            "test output",
            null,
            "2024-01-01T00:00:00Z"
        );
        
        ExecutionTrace trace = new ExecutionTrace(
            "test_script.loom",
            "testWorkflow",
            List.of(step)
        );
        
        assertEquals("test_script.loom", trace.scriptName());
        assertEquals("testWorkflow", trace.workflowName());
        assertEquals(1, trace.steps().size());
        assertEquals("delegate", trace.steps().get(0).kind());
    }
    
    @Test
    void testTraceStepWithNullableFields() {
        TraceStep step = new TraceStep(
            "handoff",
            "TestAgent",
            null,  // nullable payload
            null,  // nullable outputVariable
            null,  // nullable outputValue
            null,  // nullable subSteps
            null   // nullable timestamp
        );
        
        assertEquals("handoff", step.kind());
        assertEquals("TestAgent", step.agentName());
        assertNull(step.payload());
        assertNull(step.outputVariable());
    }
    
    @Test
    void testExecutionTraceJsonSerialization() throws Exception {
        TraceStep step = new TraceStep(
            "delegate",
            "TestAgent",
            "test payload",
            "result",
            "test output",
            null,
            "2024-01-01T00:00:00Z"
        );
        
        ExecutionTrace trace = new ExecutionTrace(
            "test_script.loom",
            "testWorkflow",
            List.of(step)
        );
        
        // Serialize to JSON
        String json = objectMapper.writeValueAsString(trace);
        
        // Verify JSON contains expected fields
        assertTrue(json.contains("\"scriptName\":\"test_script.loom\""));
        assertTrue(json.contains("\"workflowName\":\"testWorkflow\""));
        assertTrue(json.contains("\"kind\":\"delegate\""));
        assertTrue(json.contains("\"agentName\":\"TestAgent\""));
        
        // Deserialize back
        ExecutionTrace deserialized = objectMapper.readValue(json, ExecutionTrace.class);
        
        assertEquals(trace.scriptName(), deserialized.scriptName());
        assertEquals(trace.workflowName(), deserialized.workflowName());
        assertEquals(trace.steps().size(), deserialized.steps().size());
    }
    
    @Test
    void testTraceStepWithSubSteps() {
        TraceStep subStep1 = new TraceStep("delegate", "Agent1", "payload1", "out1", "value1", null, null);
        TraceStep subStep2 = new TraceStep("delegate", "Agent2", "payload2", "out2", "value2", null, null);
        
        TraceStep parallelStep = new TraceStep(
            "parallel",
            null,
            null,
            null,
            null,
            List.of(subStep1, subStep2),
            "2024-01-01T00:00:00Z"
        );
        
        assertEquals("parallel", parallelStep.kind());
        assertNotNull(parallelStep.subSteps());
        assertEquals(2, parallelStep.subSteps().size());
        assertEquals("Agent1", parallelStep.subSteps().get(0).agentName());
        assertEquals("Agent2", parallelStep.subSteps().get(1).agentName());
    }
    
    @Test
    void testJsonIncludeNonNull() throws Exception {
        // Create a step with null fields
        TraceStep step = new TraceStep(
            "note",
            null,  // agentName is null
            "test note",
            null,  // outputVariable is null
            null,  // outputValue is null
            null,  // subSteps is null
            null   // timestamp is null
        );
        
        String json = objectMapper.writeValueAsString(step);
        
        // Verify null fields are not included in JSON
        assertFalse(json.contains("\"agentName\""));
        assertFalse(json.contains("\"outputVariable\""));
        assertFalse(json.contains("\"outputValue\""));
        assertFalse(json.contains("\"subSteps\""));
        assertFalse(json.contains("\"timestamp\""));
        
        // Verify non-null fields are included
        assertTrue(json.contains("\"kind\":\"note\""));
        assertTrue(json.contains("\"payload\":\"test note\""));
    }
}
