package io.github.llm4j.mcp;

/** A request the server did not answer in time; the call may still be running on the server. */
public final class McpTimeoutException extends McpException {

    /**
     * @param message which request, and how long it waited
     */
    public McpTimeoutException(String message) {
        super(message);
    }
}
