package io.github.llm4j.mcp;

/**
 * The server refused the credential the caller handed over (a 401 after one retry with a fresh
 * value, or a 403 for a missing scope). The message starts {@code credential_rejected:} and carries
 * the server's challenge with secrets removed, so the caller can obtain a new token and resume.
 */
public final class McpCredentialRejectedException extends McpException {

    /**
     * @param message what was refused, with the redacted challenge
     */
    public McpCredentialRejectedException(String message) {
        super("credential_rejected: " + message);
    }
}
