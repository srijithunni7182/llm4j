package io.github.llm4j.mcp;

/** An MCP server answered a POST with an HTTP error status. */
public final class McpHttpException extends McpException {

    private final int status;
    private final String body;

    /**
     * @param status the HTTP status
     * @param body the start of the body, for deciding whether it is a modern protocol error
     */
    public McpHttpException(int status, String body) {
        super("the MCP server answered HTTP " + status);
        this.status = status;
        this.body = body == null ? "" : body;
    }

    /** The HTTP status. */
    public int status() {
        return status;
    }

    /** The start of the body. */
    public String body() {
        return body;
    }
}
