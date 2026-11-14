package io.github.llm4j.mcp;

/**
 * The server process ended, during a call or before one. A call that was in flight when it ended is
 * not repeated by the client, because it may have been carried out.
 */
public final class McpServerExitedException extends McpException {

    /**
     * @param message what happened and whether a restart is possible
     */
    public McpServerExitedException(String message) {
        super(message);
    }
}
