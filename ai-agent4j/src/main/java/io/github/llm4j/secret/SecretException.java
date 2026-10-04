package io.github.llm4j.secret;

/** Base of everything the secret store throws. No message ever contains a secret value; names, hosts and paths may appear. */
public class SecretException extends RuntimeException {

    public SecretException(String message) {
        super(message);
    }

    public SecretException(String message, Throwable cause) {
        super(message, cause);
    }
}
