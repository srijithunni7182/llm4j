package io.github.llm4j.secret;

import java.util.Optional;
import java.util.Set;

/**
 * Where credentials live. Components are handed a {@link SecretRef} (a name in a store) and fetch the value when they need it, so a secret is
 * never copied into a long-lived config and can be rotated while the application runs.
 *
 * <p>The library chooses nothing for the consumer: no default location, no default key. Implementations: {@link InMemorySecretStore},
 * {@link EncryptedFileSecretStore}, {@link EnvSecretStore} (read-only, for compatibility) and {@link ChainedSecretStore}. Implement this
 * interface to plug in Vault, AWS Secrets Manager or an OS keystore.
 *
 * <p>No exception thrown by a store contains a secret value.
 */
public interface SecretStore extends AutoCloseable {

    /** The secret's value. @throws SecretNotFoundException when there is none */
    String resolve(String name);

    /**
     * The secret's value for a request to {@code host}: as {@link #resolve}, but a secret restricted with
     * {@link SecretMetadata#allowedHosts()} is refused for any other host.
     *
     * @throws SecretAccessDeniedException when the host is not allowed
     */
    default String resolveFor(String name, String host) {
        SecretMetadata metadata = metadata(name).orElseThrow(() -> new SecretNotFoundException(name));
        if (!metadata.allows(host)) throw new SecretAccessDeniedException(name, String.valueOf(host));
        return resolve(name);
    }

    boolean contains(String name);

    /** The names, sorted. Names only: never values. */
    Set<String> names();

    Optional<SecretMetadata> metadata(String name);

    /** Stores a secret (a copy of {@code value}; the caller may wipe its array). Stores that cannot be written throw {@link UnsupportedOperationException}. */
    default void put(String name, char[] value, SecretMetadata metadata) {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " is read-only");
    }

    default void put(String name, char[] value) {
        put(name, value, SecretMetadata.NONE);
    }

    /** Convenience for programmatic use; prefer the {@code char[]} form where the value can be wiped afterwards. */
    default void put(String name, String value) {
        put(name, value.toCharArray(), SecretMetadata.NONE);
    }

    default void put(String name, String value, SecretMetadata metadata) {
        put(name, value.toCharArray(), metadata);
    }

    /** Removes a secret; true when there was one. */
    default boolean remove(String name) {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " is read-only");
    }

    /** Releases and, where it can, wipes what the store holds. Later use fails. */
    @Override
    default void close() { }
}
