package io.github.loom.ctk;

import java.nio.file.Path;
import java.util.List;

/**
 * Interface for executing CTK conformance tests against a Loom runtime.
 * 
 * <p>A ConformanceRunner executes canonical .loom scripts with mock agent responses and compares
 * the resulting execution traces against expected traces to validate runtime conformance.</p>
 * 
 * <p>Validates: Requirements 9.1, 9.5</p>
 */
public interface ConformanceRunner {
    
    /**
     * Executes a single conformance test case.
     * 
     * <p>Loads the .loom script, injects the mock agent server, executes the workflow,
     * captures the execution trace, and compares it against the expected trace.</p>
     * 
     * @param scriptPath path to the canonical .loom script to execute
     * @param tracePath path to the expected execution trace JSON file
     * @param mocks mock agent server providing deterministic responses
     * @return ConformanceResult indicating pass/fail and any differences
     */
    ConformanceResult run(Path scriptPath, Path tracePath, MockAgentServer mocks);
    
    /**
     * Executes all conformance test cases in a directory.
     * 
     * <p>Discovers all *.loom files in the CTK directory, matches each to its corresponding
     * trace file, runs each test case, and collects the results.</p>
     * 
     * @param ctkDir path to the CTK directory containing scripts/ and traces/ subdirectories
     * @param mocks mock agent server providing deterministic responses
     * @return list of ConformanceResult objects, one per test case
     */
    List<ConformanceResult> runAll(Path ctkDir, MockAgentServer mocks);
}
