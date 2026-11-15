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

    /**
     * Whether a client should try a modern-era request first, the way the HTTP binding does: a
     * server that does not understand it answers with a 4xx and the client falls back to {@code
     * initialize}. Stdio does not (a probe could time out), so the default is false.
     */
    default boolean probesModernFirst() {
        return false;
    }

    /** Closes the transport. */
    void close() throws Exception;
}
