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

A stdio server is a program that runs with your permissions: run it in a container or with a scrubbed environment (in Loom, `isolation: container` or `process`), keep its secrets out of its command line, and treat its tool list, annotations and results as untrusted. See [Securing integrations](../../../docs/security/integrations.md).

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

`weave connect mcp` writes this block for you after showing what the server lists, and `weave connect mcp <script> --refresh --check` notices when the server's tool list changed. See the [Loom guide](../../../src/loom/ai-agent4j-loom/LOOM_GUIDE.md) and [Securing integrations](../../../docs/security/integrations.md).


---

*Related: [OpenAPI Support](OpenAPI-Tool.md)*
