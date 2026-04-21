package io.github.loom.ctk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DefaultConformanceRunner.
 * 
 * <p>Tests the core functionality of running conformance tests, including:
 * <ul>
 *   <li>Running a single test case</li>
 *   <li>Running all test cases in a directory</li>
 *   <li>Handling missing files and directories</li>
 *   <li>Trace comparison integration</li>
 * </ul>
 * </p>
 */
class DefaultConformanceRunnerTest {
    
    @Test
    void testRunSingleTest(@TempDir Path tempDir) throws IOException {
        // Create test structure
        Path scriptPath = tempDir.resolve("test.loom");
        Path tracePath = tempDir.resolve("test.json");
        
        // Write a simple script
        Files.writeString(scriptPath, "workflow TestWorkflow() { note \"test\" }");
        
        // Write expected trace (empty steps to match stub behavior)
        String traceJson = """
            {
              "scriptName": "test.loom",
              "workflowName": "TestWorkflow",
              "steps": []
            }
            """;
        Files.writeString(tracePath, traceJson);
        
        // Create mock server
        MockAgentServer mocks = new FixtureMockAgentServer(new HashMap<>());
        
        // Run test
        DefaultConformanceRunner runner = new DefaultConformanceRunner();
        ConformanceResult result = runner.run(scriptPath, tracePath, mocks);
        
        // Verify
        assertNotNull(result);
        assertEquals("test.loom", result.testName());
        assertTrue(result.passed(), "Test should pass with matching empty traces");
        assertTrue(result.differences().isEmpty());
    }
    
    @Test
    void testRunSingleTestWithMismatch(@TempDir Path tempDir) throws IOException {
        // Create test structure
        Path scriptPath = tempDir.resolve("test.loom");
        Path tracePath = tempDir.resolve("test.json");
        
        // Write a simple script
        Files.writeString(scriptPath, "workflow TestWorkflow() { note \"test\" }");
        
        // Write expected trace with steps (will mismatch stub's empty trace)
        String traceJson = """
            {
              "scriptName": "test.loom",
              "workflowName": "TestWorkflow",
              "steps": [
                {
                  "kind": "note",
                  "payload": "test"
                }
              ]
            }
            """;
        Files.writeString(tracePath, traceJson);
        
        // Create mock server
        MockAgentServer mocks = new FixtureMockAgentServer(new HashMap<>());
        
        // Run test
        DefaultConformanceRunner runner = new DefaultConformanceRunner();
        ConformanceResult result = runner.run(scriptPath, tracePath, mocks);
        
        // Verify
        assertNotNull(result);
        assertEquals("test.loom", result.testName());
        assertFalse(result.passed(), "Test should fail with mismatched traces");
        assertFalse(result.differences().isEmpty());
        assertTrue(result.differences().get(0).contains("step count mismatch"));
    }
    
    @Test
    void testRunSingleTestWithMissingScript(@TempDir Path tempDir) throws IOException {
        // Create only trace file, not script
        Path scriptPath = tempDir.resolve("missing.loom");
        Path tracePath = tempDir.resolve("test.json");
        
        String traceJson = """
            {
              "scriptName": "test.loom",
              "workflowName": "TestWorkflow",
              "steps": []
            }
            """;
        Files.writeString(tracePath, traceJson);
        
        // Create mock server
        MockAgentServer mocks = new FixtureMockAgentServer(new HashMap<>());
        
        // Run test
        DefaultConformanceRunner runner = new DefaultConformanceRunner();
        ConformanceResult result = runner.run(scriptPath, tracePath, mocks);
        
        // Verify
        assertNotNull(result);
        assertEquals("missing.loom", result.testName());
        assertFalse(result.passed());
        assertFalse(result.differences().isEmpty());
        assertTrue(result.differences().get(0).contains("Failed to load test files"));
    }
    
    @Test
    void testRunAllWithNoScripts(@TempDir Path tempDir) throws IOException {
        // Create directory structure but no scripts
        Path scriptsDir = tempDir.resolve("scripts");
        Path tracesDir = tempDir.resolve("traces");
        Files.createDirectories(scriptsDir);
        Files.createDirectories(tracesDir);
        
        // Create mock server
        MockAgentServer mocks = new FixtureMockAgentServer(new HashMap<>());
        
        // Run all tests
        DefaultConformanceRunner runner = new DefaultConformanceRunner();
        List<ConformanceResult> results = runner.runAll(tempDir, mocks);
        
        // Verify - should return empty list when no scripts found
        assertNotNull(results);
        assertTrue(results.isEmpty(), "Should return empty list when no scripts found");
    }
    
    @Test
    void testRunAllWithMultipleScripts(@TempDir Path tempDir) throws IOException {
        // Create directory structure
        Path scriptsDir = tempDir.resolve("scripts");
        Path tracesDir = tempDir.resolve("traces");
        Files.createDirectories(scriptsDir);
        Files.createDirectories(tracesDir);
        
        // Create two test scripts and traces
        Files.writeString(scriptsDir.resolve("test1.loom"), "workflow Test1() { note \"test1\" }");
        Files.writeString(scriptsDir.resolve("test2.loom"), "workflow Test2() { note \"test2\" }");
        
        String trace1 = """
            {
              "scriptName": "test1.loom",
              "workflowName": "Test1",
              "steps": []
            }
            """;
        String trace2 = """
            {
              "scriptName": "test2.loom",
              "workflowName": "Test2",
              "steps": []
            }
            """;
        Files.writeString(tracesDir.resolve("test1.json"), trace1);
        Files.writeString(tracesDir.resolve("test2.json"), trace2);
        
        // Create mock server
        MockAgentServer mocks = new FixtureMockAgentServer(new HashMap<>());
        
        // Run all tests
        DefaultConformanceRunner runner = new DefaultConformanceRunner();
        List<ConformanceResult> results = runner.runAll(tempDir, mocks);
        
        // Verify
        assertNotNull(results);
        assertEquals(2, results.size());
        assertTrue(results.stream().anyMatch(r -> r.testName().equals("test1.loom")));
        assertTrue(results.stream().anyMatch(r -> r.testName().equals("test2.loom")));
    }
    
    @Test
    void testRunAllWithMissingTrace(@TempDir Path tempDir) throws IOException {
        // Create directory structure
        Path scriptsDir = tempDir.resolve("scripts");
        Path tracesDir = tempDir.resolve("traces");
        Files.createDirectories(scriptsDir);
        Files.createDirectories(tracesDir);
        
        // Create script but no corresponding trace
        Files.writeString(scriptsDir.resolve("test.loom"), "workflow Test() { note \"test\" }");
        
        // Create mock server
        MockAgentServer mocks = new FixtureMockAgentServer(new HashMap<>());
        
        // Run all tests
        DefaultConformanceRunner runner = new DefaultConformanceRunner();
        List<ConformanceResult> results = runner.runAll(tempDir, mocks);
        
        // Verify
        assertNotNull(results);
        assertEquals(1, results.size());
        ConformanceResult result = results.get(0);
        assertEquals("test.loom", result.testName());
        assertFalse(result.passed());
        assertTrue(result.differences().get(0).contains("Trace file not found"));
    }
    
    @Test
    void testRunAllWithMissingScriptsDirectory(@TempDir Path tempDir) {
        // Don't create scripts directory
        Path tracesDir = tempDir.resolve("traces");
        try {
            Files.createDirectories(tracesDir);
        } catch (IOException e) {
            fail("Failed to create traces directory");
        }
        
        // Create mock server
        MockAgentServer mocks = new FixtureMockAgentServer(new HashMap<>());
        
        // Run all tests
        DefaultConformanceRunner runner = new DefaultConformanceRunner();
        List<ConformanceResult> results = runner.runAll(tempDir, mocks);
        
        // Verify
        assertNotNull(results);
        assertEquals(1, results.size());
        ConformanceResult result = results.get(0);
        assertEquals("runAll", result.testName());
        assertFalse(result.passed());
        assertTrue(result.differences().get(0).contains("Scripts directory does not exist"));
    }
    
    @Test
    void testRunAllWithMissingTracesDirectory(@TempDir Path tempDir) {
        // Create only scripts directory
        Path scriptsDir = tempDir.resolve("scripts");
        try {
            Files.createDirectories(scriptsDir);
        } catch (IOException e) {
            fail("Failed to create scripts directory");
        }
        
        // Create mock server
        MockAgentServer mocks = new FixtureMockAgentServer(new HashMap<>());
        
        // Run all tests
        DefaultConformanceRunner runner = new DefaultConformanceRunner();
        List<ConformanceResult> results = runner.runAll(tempDir, mocks);
        
        // Verify
        assertNotNull(results);
        assertEquals(1, results.size());
        ConformanceResult result = results.get(0);
        assertEquals("runAll", result.testName());
        assertFalse(result.passed());
        assertTrue(result.differences().get(0).contains("Traces directory does not exist"));
    }
}
