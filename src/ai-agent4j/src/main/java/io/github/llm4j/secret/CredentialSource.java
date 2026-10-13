package io.github.llm4j.secret;

/**
 * Where a tool gets a credential <em>for each request it makes</em>, instead of holding one value for its lifetime: the credential of the
 * person a run is for. {@link #get()} is called on every request, from any thread, so an implementation must be thread-safe and should
 * return the current value (a token that was refreshed since the last call is picked up on the next request).
 *
 * <p>When the server refuses what {@link #get()} gave (a 401), the tool calls {@link #rejected()}, asks again and repeats the request once.
 * A source that caches can drop its cached value in {@link #rejected()}.
 *
 * <p>An implementation must not put the value in an exception message; a failure should be a {@link SecretException} that names the
 * credential, not the value.
 */
@FunctionalInterface
public interface CredentialSource {

    /** The credential to send now. Null or blank means there is none, which fails the request before it is sent. */
    String get() throws SecretException;

    /** Called when the server refused the value last given; the next {@link #get()} may give a different one. Does nothing by default. */
    default void rejected() {
    }

    /** A source that always gives {@code value}. */
    static CredentialSource of(String value) {
        return () -> value;
    }
}
