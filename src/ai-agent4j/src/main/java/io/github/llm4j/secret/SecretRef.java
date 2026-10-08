package io.github.llm4j.secret;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * A handle on a secret: a store and a name. Hand it to a provider or tool instead of the key itself; they call {@link #resolveFor} when they
 * make a request and keep nothing, so a secret changed in the store takes effect on the next request.
 *
 * <p>{@link #toString()} is {@code secret:<name>} and never the value. A reference is deliberately not {@link java.io.Serializable}.
 */
public final class SecretRef implements Supplier<String> {

    private final SecretStore store;
    private final String name;
    private final String literal;

    private SecretRef(SecretStore store, String name, String literal) {
        this.store = store;
        this.name = name;
        this.literal = literal;
    }

    /** The secret called {@code name} in {@code store}. The secret need not exist yet; resolving it then fails. */
    public static SecretRef of(SecretStore store, String name) {
        return new SecretRef(Objects.requireNonNull(store, "store"), SecretNames.require(name), null);
    }

    /**
     * A reference that simply carries a value the caller already has: what the {@code String} convenience overloads use. It is exactly as secure
     * as the string it wraps (held in memory, for the life of the reference).
     */
    public static SecretRef literal(String value) {
        return new SecretRef(null, "(literal)", Objects.requireNonNull(value, "value"));
    }

    /** The value, with no host check. @throws SecretNotFoundException when absent */
    public String resolve() {
        return literal != null ? literal : store.resolve(name);
    }

    /** The value for a request to {@code host}, refused if the secret is bound to other hosts. */
    public String resolveFor(String host) {
        return literal != null ? literal : store.resolveFor(name, host);
    }

    /** Whether the secret can be resolved, without resolving it for use. */
    public boolean exists() {
        return literal != null || store.contains(name);
    }

    /** The secret's name, or {@code (literal)}. */
    public String name() {
        return name;
    }

    public boolean isLiteral() {
        return literal != null;
    }

    @Override
    public String get() {
        return resolve();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SecretRef r)) return false;
        if (literal != null || r.literal != null) {
            // two literals are equal when they carry the same value (compared in constant time); a literal never equals a stored secret
            return literal != null && r.literal != null
                    && java.security.MessageDigest.isEqual(literal.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            r.literal.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return store == r.store && name.equals(r.name);
    }

    @Override
    public int hashCode() {
        // a literal's hash is a constant, so a hash never leaks anything about the value
        return literal != null ? 0x5ec7 : 31 * System.identityHashCode(store) + name.hashCode();
    }

    @Override
    public String toString() {
        return "secret:" + name;
    }
}
