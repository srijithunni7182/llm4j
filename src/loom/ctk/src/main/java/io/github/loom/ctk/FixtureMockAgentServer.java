package io.github.loom.ctk;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Implementation of MockAgentServer that loads fixture responses from JSON files.
 * 
 * <p>This implementation loads all fixture data from JSON files in the ctk/mocks/ directory
 * at construction time and provides deterministic responses based on agent name and payload.</p>
 * 
 * <p>Fixture JSON format:
 * <pre>
 * {
 *   "AgentName": {
 *     "payload1": "response1",
 *     "payload2": "response2"
 *   }
 * }
 * </pre>
 * </p>
 * 
 * <p>This class is immutable during test runs - all state is loaded at construction time
 * and cannot be modified afterward.</p>
 * 
 * <p>Validates: Requirements 8.2, 9.6</p>
 */
public class FixtureMockAgentServer implements MockAgentServer {
    
    private final Map<String, Map<String, String>> fixtures;
    private final ObjectMapper objectMapper;
    
    /**
     * Creates a FixtureMockAgentServer by loading fixture JSON files from the specified directory.
     * 
     * @param mocksDir path to the directory containing fixture JSON files
     * @throws IOException if fixture files cannot be read or parsed
     */
    public FixtureMockAgentServer(Path mocksDir) throws IOException {
        this.objectMapper = new ObjectMapper();
        this.fixtures = new HashMap<>();
        loadFixtures(mocksDir);
    }
    
    /**
     * Creates a FixtureMockAgentServer with pre-loaded fixture data.
     * 
     * <p>This constructor is useful for testing or when fixtures are loaded from a different source.</p>
     * 
     * @param fixtures map of agent names to payload-response mappings
     */
    public FixtureMockAgentServer(Map<String, Map<String, String>> fixtures) {
        this.objectMapper = new ObjectMapper();
        this.fixtures = new HashMap<>();
        // Deep defensive copy - copy both outer and inner maps
        fixtures.forEach((agentName, payloadMap) -> {
            this.fixtures.put(agentName, new HashMap<>(payloadMap));
        });
    }
    
    /**
     * Loads all fixture JSON files from the specified directory.
     * 
     * @param mocksDir path to the directory containing fixture JSON files
     * @throws IOException if fixture files cannot be read or parsed
     */
    private void loadFixtures(Path mocksDir) throws IOException {
        if (!Files.exists(mocksDir) || !Files.isDirectory(mocksDir)) {
            throw new IOException("Mocks directory does not exist or is not a directory: " + mocksDir);
        }
        
        Files.list(mocksDir)
            .filter(path -> path.toString().endsWith(".json"))
            .forEach(path -> {
                try {
                    loadFixtureFile(path);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to load fixture file: " + path, e);
                }
            });
    }
    
    /**
     * Loads a single fixture JSON file and merges it into the fixtures map.
     * 
     * @param fixturePath path to the fixture JSON file
     * @throws IOException if the file cannot be read or parsed
     */
    @SuppressWarnings("unchecked")
    private void loadFixtureFile(Path fixturePath) throws IOException {
        String content = Files.readString(fixturePath);
        
        // Construct the nested map type: Map<String, Map<String, String>>
        var innerMapType = objectMapper.getTypeFactory().constructMapType(
            Map.class,
            objectMapper.getTypeFactory().constructType(String.class),
            objectMapper.getTypeFactory().constructType(String.class)
        );
        var outerMapType = objectMapper.getTypeFactory().constructMapType(
            Map.class,
            objectMapper.getTypeFactory().constructType(String.class),
            innerMapType
        );
        
        Map<String, Map<String, String>> fixtureData = objectMapper.readValue(content, outerMapType);
        
        // Merge fixture data into the main fixtures map
        fixtureData.forEach((agentName, payloadMap) -> {
            fixtures.computeIfAbsent(agentName, k -> new HashMap<>()).putAll(payloadMap);
        });
    }
    
    /**
     * Returns a deterministic response for the given agent and payload.
     * 
     * <p>If no matching response is found in the fixtures, returns a default response
     * indicating the missing fixture.</p>
     * 
     * @param agentName the name of the agent being called
     * @param payload the input payload sent to the agent
     * @return the mock response for this agent/payload combination
     */
    @Override
    public String getResponse(String agentName, String payload) {
        Map<String, String> agentFixtures = fixtures.get(agentName);
        if (agentFixtures == null) {
            return String.format("[MOCK] No fixtures found for agent: %s", agentName);
        }
        
        String response = agentFixtures.get(payload);
        if (response == null) {
            return String.format("[MOCK] No response found for agent '%s' with payload: %s", agentName, payload);
        }
        
        return response;
    }
    
    /**
     * Returns the number of agents with loaded fixtures.
     * 
     * @return count of agents in the fixtures map
     */
    public int getAgentCount() {
        return fixtures.size();
    }
    
    /**
     * Returns the number of payload-response mappings for a specific agent.
     * 
     * @param agentName the name of the agent
     * @return count of payload-response mappings, or 0 if agent not found
     */
    public int getPayloadCount(String agentName) {
        Map<String, String> agentFixtures = fixtures.get(agentName);
        return agentFixtures != null ? agentFixtures.size() : 0;
    }
}
