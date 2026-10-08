package io.github.llm4j.secret;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/** A store filled by the program itself. Values are held as {@code char[]} and wiped on {@link #remove} and {@link #close}. Thread-safe. */
public final class InMemorySecretStore implements SecretStore {

    private record Entry(char[] value, SecretMetadata metadata) { }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private volatile boolean closed;

    @Override
    public String resolve(String name) {
        ensureOpen();
        Entry e = entries.get(name);
        if (e == null) throw new SecretNotFoundException(name);
        return new String(e.value());
    }

    @Override
    public boolean contains(String name) {
        ensureOpen();
        return name != null && entries.containsKey(name);
    }

    @Override
    public Set<String> names() {
        ensureOpen();
        return Collections.unmodifiableSet(new TreeSet<>(entries.keySet()));
    }

    @Override
    public Optional<SecretMetadata> metadata(String name) {
        ensureOpen();
        Entry e = name == null ? null : entries.get(name);
        return e == null ? Optional.empty() : Optional.of(e.metadata());
    }

    @Override
    public void put(String name, char[] value, SecretMetadata metadata) {
        ensureOpen();
        SecretNames.require(name);
        if (value == null || value.length == 0) throw new IllegalArgumentException("a secret's value must not be empty");
        SecretMetadata m = (metadata == null ? SecretMetadata.NONE : metadata).withUpdatedAt(Instant.now());
        Entry previous = entries.put(name, new Entry(value.clone(), m));
        if (previous != null) Arrays.fill(previous.value(), '\0');
    }

    @Override
    public boolean remove(String name) {
        ensureOpen();
        Entry e = name == null ? null : entries.remove(name);
        if (e == null) return false;
        Arrays.fill(e.value(), '\0');
        return true;
    }

    @Override
    public void close() {
        closed = true;
        entries.values().forEach(e -> Arrays.fill(e.value(), '\0'));
        entries.clear();
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("this secret store is closed");
    }
}
