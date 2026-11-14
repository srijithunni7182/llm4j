package io.github.llm4j.mcp;

/**
 * A tool call the server answered with {@code isError: true}: the tool ran and failed. The message
 * is the text the server gave, which is untrusted content.
 */
public final class McpToolException extends McpException {

    /**
     * @param message the error text from the tool result
     */
    public McpToolException(String message) {
        super(message);
    }
}
