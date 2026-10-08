package io.github.llm4j.secret;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Looks a name up in several stores, in order; the first store that has it wins. Read-only. Closing the chain closes the stores. */
public final class ChainedSecretStore implements SecretStore {

    private final List<SecretStore> stores;

    private ChainedSecretStore(List<SecretStore> stores) {
        this.stores = stores;
    }

    public static ChainedSecretStore of(SecretStore... stores) {
        if (stores.length == 0) throw new IllegalArgumentException("a chain needs at least one store");
        return new ChainedSecretStore(List.copyOf(List.of(stores)));
    }

    private SecretStore holder(String name) {
        for (SecretStore s : stores) if (s.contains(name)) return s;
        return null;
    }

    @Override
    public String resolve(String name) {
        SecretStore s = holder(name);
        if (s == null) throw new SecretNotFoundException(String.valueOf(name));
        return s.resolve(name);
    }

    @Override
    public String resolveFor(String name, String host) {
        SecretStore s = holder(name);
        if (s == null) throw new SecretNotFoundException(String.valueOf(name));
        return s.resolveFor(name, host);
    }

    @Override
    public boolean contains(String name) {
        return holder(name) != null;
    }

    @Override
    public Set<String> names() {
        Set<String> all = new TreeSet<>();
        for (SecretStore s : stores) all.addAll(s.names());
        return Collections.unmodifiableSet(all);
    }

    @Override
    public Optional<SecretMetadata> metadata(String name) {
        SecretStore s = holder(name);
        return s == null ? Optional.empty() : s.metadata(name);
    }

    @Override
    public void close() {
        List<RuntimeException> failures = new ArrayList<>();
        for (SecretStore s : stores) {
            try {
                s.close();
            } catch (RuntimeException e) {
                failures.add(e);
            }
        }
        if (!failures.isEmpty()) throw failures.get(0);
    }
}
