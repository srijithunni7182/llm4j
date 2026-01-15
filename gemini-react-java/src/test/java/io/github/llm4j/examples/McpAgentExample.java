package io.github.llm4j.examples;

import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.mcp.McpClient;
import io.github.llm4j.mcp.McpToolAdapter;
import io.github.llm4j.mcp.StdioMcpTransport;

import java.util.List;
import java.util.Map;

/**
 * Example of using MCP with Gemini ReAct Agent.
 */
public class McpAgentExample {

    public static void main(String[] args) throws Exception {
        // 1. Start MCP Client
        // Use the Real MCP Filesystem Server via npx
        // We expose the current project root directory
        String projectRoot = System.getProperty("user.dir");
        StdioMcpTransport transport = new StdioMcpTransport(
                List.of("npx", "-y", "@modelcontextprotocol/server-filesystem", projectRoot),
                null);
        try (McpClient mcpClient = new McpClient(transport)) {
            mcpClient.initialize();

            // 2. Discover Tools
            List<Map<String, Object>> mcpTools = mcpClient.listTools();

            // 3. Create Agent Builder
            // This is hypothetical usage, assuming we have a builder or constructor
            // Since ReActAgent might not have a public builder in this snippet,
            // we'll demonstrate the adaptation part.

            System.out.println("Discovered " + mcpTools.size() + " MCP tools.");

            for (Map<String, Object> toolDef : mcpTools) {
                McpToolAdapter adapter = new McpToolAdapter(mcpClient, toolDef);
                System.out.println("Adapted Tool: " + adapter.getName());

                // agent.registerTool(adapter);
            }

            // 4. Run Agent
            // ...
        }
    }
}
