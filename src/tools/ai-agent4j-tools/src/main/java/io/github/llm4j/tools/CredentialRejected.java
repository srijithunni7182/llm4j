package io.github.llm4j.tools;

/**
 * The system a tool called refused the credential the caller handed over: a 401 that stayed after
 * one retry with a fresh value, or a 403 for a missing scope. Nothing was done. The caller can act
 * on it (obtain a new token, or a wider one) and resume the run.
 *
 * <p>The message carries the server's {@code WWW-Authenticate} challenge with any secret removed, so
 * the caller can start its own step-up; it never carries a credential.
 */
public final class CredentialRejected extends ToolRefusal {

    /**
     * @param message what was refused and by whom, including the redacted challenge
     */
    public CredentialRejected(String message) {
        super(message);
    }
}
