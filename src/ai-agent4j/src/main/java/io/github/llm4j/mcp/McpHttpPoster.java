package io.github.llm4j.mcp;

import java.io.IOException;
import java.util.Map;

/**
 * How a Streamable HTTP transport sends one POST. The core has no address rules of its own, so the
 * host supplies this (Loom's runs every request through {@code NetPolicy} and the shared HTTP
 * layer); a transport never opens a connection itself.
 */
@FunctionalInterface
public interface McpHttpPoster {

    /**
     * A reply.
     *
     * @param status the HTTP status
     * @param headers the response headers, names in lower case
     * @param body the whole body (a response stream is read until it ends)
     */
    record Reply(int status, Map<String, String> headers, byte[] body) {
        /** The value of a header, or null. */
        public String header(String name) {
            return headers.get(name.toLowerCase(java.util.Locale.ROOT));
        }
    }

    /**
     * POSTs {@code body} to {@code url} with {@code headers}.
     *
     * @throws IOException when the request could not be made or was refused by the host's rules
     */
    Reply post(String url, Map<String, String> headers, byte[] body) throws IOException;
}
