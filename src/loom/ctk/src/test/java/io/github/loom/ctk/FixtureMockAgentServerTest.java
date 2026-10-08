package io.github.loom.ctk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FixtureMockAgentServer.
 */
class FixtureMockAgentServerTest {
    
    @Test
    void testFixtureMockAgentServerWithPreloadedData() {
        Map<String, Map<String, String>> fixtures = new HashMap<>();
        Map<String, String> agent1Fixtures = new HashMap<>();
        agent1Fixtures.put("payload1", "response1");
        agent1Fixtures.put("payload2", "response2");
        fixtures.put("Agent1", agent1Fixtures);
        
        FixtureMockAgentServer server = new FixtureMockAgentServer(fixtures);
        
        assertEquals("response1", server.getResponse("Agent1", "payload1"));
        assertEquals("response2", server.getResponse("Agent1", "payload2"));
        assertEquals(1, server.getAgentCount());
        assertEquals(2, server.getPayloadCount("Agent1"));
    }
    
    @Test
    void testFixtureMockAgentServerWithMissingAgent() {
        Map<String, Map<String, String>> fixtures = new HashMap<>();
        FixtureMockAgentServer server = new FixtureMockAgentServer(fixtures);
        
        String response = server.getResponse("UnknownAgent", "payload");
        assertTrue(response.contains("[MOCK]"));
        assertTrue(response.contains("No fixtures found for agent: UnknownAgent"));
    }
    
    @Test
    void testFixtureMockAgentServerWithMissingPayload() {
        Map<String, Map<String, String>> fixtures = new HashMap<>();
        Map<String, String> agent1Fixtures = new HashMap<>();
        agent1Fixtures.put("payload1", "response1");
        fixtures.put("Agent1", agent1Fixtures);
        
        FixtureMockAgentServer server = new FixtureMockAgentServer(fixtures);
        
        String response = server.getResponse("Agent1", "unknownPayload");
        assertTrue(response.contains("[MOCK]"));
        assertTrue(response.contains("No response found for agent 'Agent1'"));
        assertTrue(response.contains("unknownPayload"));
    }
    
    @Test
    void testFixtureMockAgentServerLoadFromDirectory(@TempDir Path tempDir) throws IOException {
        // Create a fixture JSON file
        Path fixtureFile = tempDir.resolve("test_fixture.json");
        String fixtureJson = """
            {
              "TestAgent": {
                "hello": "world",
                "foo": "bar"
              },
              "AnotherAgent": {
                "test": "response"
              }
            }
            """;
        Files.writeString(fixtureFile, fixtureJson);
        
        FixtureMockAgentServer server = new FixtureMockAgentServer(tempDir);
        
        assertEquals("world", server.getResponse("TestAgent", "hello"));
        assertEquals("bar", server.getResponse("TestAgent", "foo"));
        assertEquals("response", server.getResponse("AnotherAgent", "test"));
        assertEquals(2, server.getAgentCount());
        assertEquals(2, server.getPayloadCount("TestAgent"));
        assertEquals(1, server.getPayloadCount("AnotherAgent"));
    }
    
    @Test
    void testFixtureMockAgentServerLoadMultipleFiles(@TempDir Path tempDir) throws IOException {
        // Create first fixture file
        Path fixture1 = tempDir.resolve("fixture1.json");
        String json1 = """
            {
              "Agent1": {
                "payload1": "response1"
              }
            }
            """;
        Files.writeString(fixture1, json1);
        
        // Create second fixture file
        Path fixture2 = tempDir.resolve("fixture2.json");
        String json2 = """
            {
              "Agent2": {
                "payload2": "response2"
              }
            }
            """;
        Files.writeString(fixture2, json2);
        
        FixtureMockAgentServer server = new FixtureMockAgentServer(tempDir);
        
        assertEquals("response1", server.getResponse("Agent1", "payload1"));
        assertEquals("response2", server.getResponse("Agent2", "payload2"));
        assertEquals(2, server.getAgentCount());
    }
    
    @Test
    void testFixtureMockAgentServerMergesFixtures(@TempDir Path tempDir) throws IOException {
        // Create two fixture files with the same agent
        Path fixture1 = tempDir.resolve("fixture1.json");
        String json1 = """
            {
              "Agent1": {
                "payload1": "response1"
              }
            }
            """;
        Files.writeString(fixture1, json1);
        
        Path fixture2 = tempDir.resolve("fixture2.json");
        String json2 = """
            {
              "Agent1": {
                "payload2": "response2"
              }
            }
            """;
        Files.writeString(fixture2, json2);
        
        FixtureMockAgentServer server = new FixtureMockAgentServer(tempDir);
        
        // Both payloads should be available for Agent1
        assertEquals("response1", server.getResponse("Agent1", "payload1"));
        assertEquals("response2", server.getResponse("Agent1", "payload2"));
        assertEquals(1, server.getAgentCount());
        assertEquals(2, server.getPayloadCount("Agent1"));
    }
    
    @Test
    void testFixtureMockAgentServerInvalidDirectory() {
        Path nonExistentDir = Path.of("/nonexistent/directory");
        
        assertThrows(IOException.class, () -> {
            new FixtureMockAgentServer(nonExistentDir);
        });
    }
    
    @Test
    void testFixtureMockAgentServerImmutability() {
        Map<String, Map<String, String>> fixtures = new HashMap<>();
        Map<String, String> agent1Fixtures = new HashMap<>();
        agent1Fixtures.put("payload1", "response1");
        fixtures.put("Agent1", agent1Fixtures);
        
        FixtureMockAgentServer server = new FixtureMockAgentServer(fixtures);
        
        // Modify the original fixtures map
        agent1Fixtures.put("payload2", "response2");
        fixtures.put("Agent2", new HashMap<>());
        
        // Server should not be affected by external modifications
        assertEquals(1, server.getAgentCount());
        assertEquals(1, server.getPayloadCount("Agent1"));
        assertEquals(0, server.getPayloadCount("Agent2"));
    }
}
