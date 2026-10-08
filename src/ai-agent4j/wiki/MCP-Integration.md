# Model Context Protocol (MCP) Integration

The Model Context Protocol (MCP) is an open standard that enables LLMs to connect with external data and tools seamlessly. `ai-agent4j` provides a native MCP client that turns any MCP server into a set of Java `Tool` instances.

## Why use MCP?

- **Interoperability**: Connect to tools written in any language (Python, Node, Go).
- **Growing Ecosystem**: Access a library of pre-built servers for Filesystems, GitHub, SQLite, Slack, and more.
- **Standardization**: Use the same protocol for all your external integrations.

## Setting up an MCP Connection

To connect to an MCP server, you first define a **Transport**. The most common transport is `StdioMcpTransport`, which communicates with a local process via standard input/output.

```java
import io.github.llm4j.mcp.McpClient;
import io.github.llm4j.mcp.StdioMcpTransport;
import io.github.llm4j.agent.adapter.McpToolAdapter;
import java.util.List;

// 1. Define Transport (e.g. connecting to a local filesystem server via npx)
StdioMcpTransport transport = new StdioMcpTransport(
    List.of("npx", "-y", "@modelcontextprotocol/server-filesystem", "."), 
    null
);

// 2. Initialize the MCP Client
McpClient mcpClient = new McpClient(transport);
mcpClient.initialize();
```

## Adding MCP Tools to your Agent

Once the client is initialized, you can list the available tools and adapt them for use in your `ReActAgent`.

```java
ReActAgent.Builder agentBuilder = ReActAgent.builder()
    .llmClient(client);

// Add all tools discovered on the MCP server to our agent
for (var toolDef : mcpClient.listTools()) {
    agentBuilder.addTool(new McpToolAdapter(mcpClient, toolDef));
}

ReActAgent agent = agentBuilder.build();

// Now the agent can use filesystem tools like 'read_file', 'list_dir', etc.
agent.run("What files are in the current directory?");
```

## Advanced Usage

### Multiple MCP Servers

You can connect an agent to multiple MCP servers by initializing multiple clients and adding tools from all of them to the same `ReActAgent`.

### Security and Permissions

MCP servers often require specific permissions (e.g., directory access). Ensure the command line defined in your transport has the necessary arguments to authorize these actions.

---

*Related: [OpenAPI Support](OpenAPI-Tool.md)*
