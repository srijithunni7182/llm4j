package io.github.loom.ctk;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Represents a single step in an execution trace.
 * 
 * <p>Each step captures the execution of a single Loom statement (delegate, handoff, broadcast, etc.)
 * with its associated metadata. Steps may contain nested sub-steps for parallel and call statements.</p>
 * 
 * <p>Validates: Requirements 22.1, 22.2, 22.3</p>
 */
public record TraceStep(
    @JsonProperty("kind") String kind,
    @JsonProperty("agentName") @JsonInclude(JsonInclude.Include.NON_NULL) String agentName,
    @JsonProperty("payload") @JsonInclude(JsonInclude.Include.NON_NULL) String payload,
    @JsonProperty("outputVariable") @JsonInclude(JsonInclude.Include.NON_NULL) String outputVariable,
    @JsonProperty("outputValue") @JsonInclude(JsonInclude.Include.NON_NULL) String outputValue,
    @JsonProperty("subSteps") @JsonInclude(JsonInclude.Include.NON_NULL) List<TraceStep> subSteps,
    @JsonProperty("timestamp") @JsonInclude(JsonInclude.Include.NON_NULL) String timestamp
) {
    /**
     * Creates a TraceStep with all fields.
     * 
     * @param kind one of: delegate, handoff, broadcast, note, call, parallel, observe
     * @param agentName the name of the agent executing this step (nullable)
     * @param payload the input payload for this step (nullable)
     * @param outputVariable the variable name where output is stored (nullable)
     * @param outputValue the actual output value (nullable, not compared in CTK)
     * @param subSteps nested steps for parallel/call statements (nullable)
     * @param timestamp ISO-8601 timestamp of step execution (nullable)
     */
    public TraceStep {
        // Compact constructor - validation can be added here if needed
    }
}
