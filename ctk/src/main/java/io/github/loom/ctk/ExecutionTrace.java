package io.github.loom.ctk;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Represents the complete execution trace of a Loom workflow.
 * 
 * <p>An execution trace captures the ordered sequence of statement executions produced by a runtime
 * when running a workflow. It includes the script name, workflow name, and all execution steps.</p>
 * 
 * <p>Execution traces are used by the CTK to validate runtime conformance by comparing actual traces
 * against expected canonical traces.</p>
 * 
 * <p>Validates: Requirements 22.1, 22.2, 22.3</p>
 */
public record ExecutionTrace(
    @JsonProperty("scriptName") String scriptName,
    @JsonProperty("workflowName") String workflowName,
    @JsonProperty("steps") List<TraceStep> steps
) {
    /**
     * Creates an ExecutionTrace.
     * 
     * @param scriptName the name of the .loom script that was executed
     * @param workflowName the name of the workflow that was executed
     * @param steps the ordered list of execution steps (must be non-empty for executed workflows)
     */
    public ExecutionTrace {
        // Compact constructor - validation can be added here if needed
    }
}
