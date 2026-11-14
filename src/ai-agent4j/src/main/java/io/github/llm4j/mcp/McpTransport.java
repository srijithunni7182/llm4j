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

    /**
     * Registers a handler told, once, when the connection ends without {@link #close()} having been
     * called (a server process that exited, a stream that broke). The argument says why, without
     * secrets. A transport that cannot tell leaves this as is.
     */
    default void onClose(Consumer<String> handler) {}

    /** Closes the transport. */
    void close() throws Exception;
}
