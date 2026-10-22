package io.github.llm4j.secret;

/**
 * Where a tool gets a credential from <em>each time it makes a request</em>. It is the tool-side
 * view of {@link InvokerCredentials}: the tool holds a source, never the value, so a token that was
 * refreshed since the last request is used on the next.
 */
public interface CredentialSource {

    /**
     * The value now. @throws SecretException when it cannot be obtained; the message never contains
     * a value
     */
    String get();

    /**
     * The server refused the value last returned. The next {@link #get} should return a fresh one.
     * The default does nothing.
     */
    default void rejected() {}

    /** A source that always returns {@code value}. */
    static CredentialSource of(String value) {
        java.util.Objects.requireNonNull(value, "value");
        return () -> value;
    }
}
