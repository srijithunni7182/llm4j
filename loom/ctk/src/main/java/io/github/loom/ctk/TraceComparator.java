package io.github.loom.ctk;

import java.util.ArrayList;
import java.util.List;

/**
 * Compares execution traces for structural conformance.
 * 
 * <p>The TraceComparator validates that an actual execution trace matches an expected canonical
 * trace structurally. It compares workflow names, step counts, and individual step fields
 * (kind, agentName, outputVariable) but explicitly excludes outputValue from comparison since
 * LLM responses are non-deterministic.</p>
 * 
 * <p>Validates: Requirements 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7, 10.8</p>
 */
public class TraceComparator {
    
    /**
     * Compares an actual execution trace against an expected canonical trace.
     * 
     * <p>The comparison is structural only - it validates that the workflow name, step count,
     * and step metadata (kind, agentName, outputVariable) match between traces. The outputValue
     * field is explicitly NOT compared because LLM responses are non-deterministic.</p>
     * 
     * @param actual the execution trace produced by running the script against the runtime
     * @param expected the canonical trace loaded from ctk/traces/*.json
     * @return a ConformanceResult with passed=true if traces are structurally equivalent,
     *         or passed=false with a list of human-readable differences
     */
    public static ConformanceResult compareTraces(ExecutionTrace actual, ExecutionTrace expected) {
        List<String> differences = new ArrayList<>();
        
        // Check workflow name equality
        if (!actual.workflowName().equals(expected.workflowName())) {
            differences.add("workflowName mismatch: " + actual.workflowName() + " vs " + expected.workflowName());
            // Early return on workflow name mismatch per algorithm
            return new ConformanceResult(null, false, differences);
        }
        
        // Check step count equality
        if (actual.steps().size() != expected.steps().size()) {
            differences.add("step count mismatch: " + actual.steps().size() + " vs " + expected.steps().size());
        }
        
        // Compare individual steps up to the minimum step count
        int minSteps = Math.min(actual.steps().size(), expected.steps().size());
        for (int i = 0; i < minSteps; i++) {
            TraceStep actualStep = actual.steps().get(i);
            TraceStep expectedStep = expected.steps().get(i);
            
            // Compare kind
            if (!actualStep.kind().equals(expectedStep.kind())) {
                differences.add("[step " + i + "] kind: " + actualStep.kind() + " vs " + expectedStep.kind());
            }
            
            // Compare agentName (handling nulls)
            if (!areEqual(actualStep.agentName(), expectedStep.agentName())) {
                differences.add("[step " + i + "] agentName: " + actualStep.agentName() + " vs " + expectedStep.agentName());
            }
            
            // Compare outputVariable (handling nulls)
            if (!areEqual(actualStep.outputVariable(), expectedStep.outputVariable())) {
                differences.add("[step " + i + "] outputVariable: " + actualStep.outputVariable() + " vs " + expectedStep.outputVariable());
            }
            
            // Note: outputValue is NOT compared - LLM responses are non-deterministic
        }
        
        // Return result with passed=true if no differences found
        return new ConformanceResult(null, differences.isEmpty(), differences);
    }
    
    /**
     * Helper method to compare two potentially null strings for equality.
     * 
     * @param a first string (may be null)
     * @param b second string (may be null)
     * @return true if both are null or both are non-null and equal
     */
    private static boolean areEqual(String a, String b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.equals(b);
    }
}
