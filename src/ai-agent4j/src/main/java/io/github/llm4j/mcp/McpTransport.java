package io.github.llm4j.mcp;

import java.io.IOException;
import java.util.function.Consumer;

public interface McpTransport extends AutoCloseable {
    /** Connects to the MCP server. */
    void connect() throws IOException;

    /** Sends a JSON-RPC message to the server. */
    void sendMessage(JsonRpcMessage message) throws IOException;

    /** Registers a handler for incoming messages. */
    void onMessage(Consumer<JsonRpcMessage> handler);

    /** Closes the transport. */
    void close() throws Exception;
}
