package io.github.llm4j.mcp;

/** A problem talking to an MCP server: the base of the exceptions the client throws. */
public class McpException extends RuntimeException {

    /**
     * @param message what went wrong and what to do about it
     */
    public McpException(String message) {
        super(message);
    }

    /**
     * @param message what went wrong and what to do about it
     * @param cause the underlying failure
     */
    public McpException(String message, Throwable cause) {
        super(message, cause);
    }
}
