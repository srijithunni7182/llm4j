package io.github.llm4j.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.agent.Tool;

import java.util.Map;

public class McpToolAdapter implements Tool {
    private final McpClient client;
    private final String name;
    private final String description;
    private final Map<String, Object> inputSchema;
    private final ObjectMapper objectMapper;

    public McpToolAdapter(McpClient client, Map<String, Object> toolDef) {
        this.client = client;
        this.name = (String) toolDef.get("name");
        this.description = (String) toolDef.get("description");
        this.inputSchema = (Map<String, Object>) toolDef.get("inputSchema");
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        // We could verify schema here or append it to description if needed for Gemini
        return description;
    }

    @Override
    public String execute(Map<String, Object> args) throws Exception {
        Object result = client.callTool(name, args);

        // Convert result to JSON string as Tool.execute returns String
        if (result instanceof String) {
            return (String) result;
        } else {
            return objectMapper.writeValueAsString(result);
        }
    }
}
