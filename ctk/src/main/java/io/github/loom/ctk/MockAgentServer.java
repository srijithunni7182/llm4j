package io.github.loom.ctk;

/**
 * Interface for providing mock agent responses during CTK test execution.
 * 
 * <p>A MockAgentServer provides deterministic responses for agent calls, allowing CTK tests
 * to validate runtime behavior without depending on real LLM providers.</p>
 * 
 * <p>Implementations must be immutable during a test run - all state should be loaded at
 * construction time and not modified during test execution.</p>
 * 
 * <p>Validates: Requirements 8.2, 9.6</p>
 */
public interface MockAgentServer {
    
    /**
     * Returns a deterministic response for the given agent and payload.
     * 
     * <p>The response is looked up from pre-loaded fixture data. If no matching response
     * is found, implementations should return a default response or throw an exception.</p>
     * 
     * @param agentName the name of the agent being called
     * @param payload the input payload sent to the agent
     * @return the mock response for this agent/payload combination
     */
    String getResponse(String agentName, String payload);
}
