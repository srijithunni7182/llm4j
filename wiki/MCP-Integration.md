# Model Context Protocol (MCP) Integration

**ai-agent4j** includes full support for the **Model Context Protocol (MCP)**. This is a game-changer for AI agents, as it allows your Java-based ReAct agents to connect to any external MCP server—whether it's written in Python, Node.js, or Go—and automatically discover and use its tools.

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

* [x] **Protocol eras.** The client offers `2025-11-25` and accepts `2025-06-18`, `2025-03-26` and `2024-11-05` (the `initialize` handshake), and speaks the `2026-07-28` revision (`server/discover`, the protocol version in each request's `_meta` and in the `MCP-Protocol-Version`, `Mcp-Method` and `Mcp-Name` headers) when a server answers it; an `UnsupportedProtocolVersionError` (-32022) is answered by trying the version the server names.
* [x] **Transports**: Stdio, and **Streamable HTTP** (`McpHttpTransport`: a reply as JSON or as a server-sent event stream, `Mcp-Session-Id` in the older era), with the caller's bearer token asked for on every request and never kept.
* [x] **Capabilities**: `tools/list` (paged), `tools/call`
* [x] **Tool annotations are read as untrusted.** `McpEffects.propose` turns a tool's annotations into a *proposal* of its effect (an absent `readOnlyHint` is taken to change things), for a person to approve; nothing acts on them by itself.
* [ ] `resources/*`, `prompts/*`, sampling, roots, logging, elicitation and the multi-round-trip `input_required` result (a call that returns it fails with a clear message)

## In a Loom script

In Loom an MCP server is a block, with the isolation, effects and a pin written down:

```text
mcp Files {
    transport: "stdio"
    cmd: "npx"
    args: ["-y", "@modelcontextprotocol/server-filesystem", "data"]
    isolation: container
    image: "node:22"
    workspace: "data"
    effects: { read_file: reads, write_file: changes }
    pin: "sha256-0123456789abcdef"
}
```

`weave connect mcp` writes this block for you after showing what the server lists, and `weave connect mcp <script> --refresh --check` notices when the server's tool list changed. See the [Loom guide](../src/loom/ai-agent4j-loom/LOOM_GUIDE.md) and [Securing integrations](../docs/security/integrations.md).
