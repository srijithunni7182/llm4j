package io.github.loom.ctk;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Represents the result of a single CTK conformance test case.
 * 
 * <p>A conformance result indicates whether a runtime's execution trace matched the expected
 * canonical trace, and if not, provides human-readable descriptions of the differences.</p>
 * 
 * <p>Validates: Requirements 9.1, 9.4</p>
 */
public record ConformanceResult(
    @JsonProperty("testName") String testName,
    @JsonProperty("passed") boolean passed,
    @JsonProperty("differences") List<String> differences
) {
    /**
     * Creates a ConformanceResult.
     * 
     * @param testName the name of the test case
     * @param passed true if the actual trace matched the expected trace structurally
     * @param differences list of human-readable difference descriptions (empty when passed is true)
     */
    public ConformanceResult {
        // Compact constructor - validation can be added here if needed
    }
}
