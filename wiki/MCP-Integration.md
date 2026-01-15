# Model Context Protocol (MCP) Integration

**gemini-react-java** includes full support for the **Model Context Protocol (MCP)**. This is a game-changer for AI agents, as it allows your Java-based ReAct agents to connect to any external MCP server—whether it's written in Python, Node.js, or Go—and automatically discover and use its tools.

## What is MCP?

The Model Context Protocol (MCP) is an open standard that allows developers to expose data and functionality to AI models. Instead of hardcoding tools into your agent, you run an "MCP Server" (like a filesystem server, a database server, or a browser controller), and your agent connects to it as an "MCP Client".

## Architecture

We provide a lightweight, dependency-free implementation of the MCP Client in pure Java:

* **`io.github.llm4j.mcp.McpClient`**: The core client that manages the JSON-RPC 2.0 handshake and tool discovery.
* **`io.github.llm4j.mcp.StdioMcpTransport`**: A transport layer that uses `ProcessBuilder` to spawn and communicate with external MCP servers via `stdin`/`stdout`.
* **`io.github.llm4j.mcp.McpToolAdapter`**: A bridge that automatically converts discovered MCP tools into `Gemini` compatible `Tool` objects.

## Usage Guide

### 1. Prerequisite: Have an MCP Server

You need an executable MCP server. This could be a Python script or an NPM package.
Example: `@modelcontextprotocol/server-filesystem`

```bash
npx -y @modelcontextprotocol/server-filesystem /path/to/share
```

### 2. Connect in Java

```java
import io.github.llm4j.mcp.*;
import io.github.llm4j.agent.ReActAgent;
import java.util.List;

public class MyMcpAgent {
    public static void main(String[] args) throws Exception {
        
        // 1. Configure the Transport (spawn the server process)
        StdioMcpTransport transport = new StdioMcpTransport(
            List.of("npx", "-y", "@modelcontextprotocol/server-filesystem", "."), 
            null
        );
        
        // 2. Initialize the Client
        try (McpClient mcpClient = new McpClient(transport)) {
            mcpClient.initialize();
            
            // 3. List available tools
            var tools = mcpClient.listTools();
            System.out.println("Found tools: " + tools.size());
            
            // 4. Register with your Agent
            // (Assuming you have a method to register tools)
            for (var toolDef : tools) {
                McpToolAdapter adapter = new McpToolAdapter(mcpClient, toolDef);
                myAgentBuilder.addTool(adapter);
            }
            
            // 5. Run your Agent!
            // The agent can now "read_file", "list_directory", etc.
        }
    }
}
```

## Supported Features

* [x] **Core Protocol**: Version `2024-11-05`
* [x] **Transports**: Stdio (Standard Input/Output)
* [x] **Capabilities**:
  * [x] `tools/list`
  * [x] `tools/call`
  * [ ] `resources/list` (Coming Soon)
  * [ ] `resources/read` (Coming Soon)
