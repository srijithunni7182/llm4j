package io.github.llm4j.secret;

/** The store could not be read or written: a wrong master key, a damaged or tampered file, a file changed by someone else, a refused permission. */
public class SecretStoreException extends SecretException {

    public SecretStoreException(String message) {
        super(message);
    }

    public SecretStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
