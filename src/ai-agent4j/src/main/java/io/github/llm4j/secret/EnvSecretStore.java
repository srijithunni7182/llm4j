package io.github.llm4j.secret;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * A read-only view of environment variables, for compatibility and for {@code weave secrets import-env}: a secret's name is the variable's
 * name. It cannot restrict hosts. Prefer an encrypted store for anything you keep.
 */
public final class EnvSecretStore implements SecretStore {

    private final Function<String, String> env;
    private final Set<String> names;

    private EnvSecretStore(Function<String, String> env, Set<String> names) {
        this.env = env;
        this.names = names;
    }

    /** The process environment. */
    public static EnvSecretStore system() {
        Set<String> names = new TreeSet<>();
        for (String n : System.getenv().keySet()) if (SecretNames.isValid(n)) names.add(n);
        return new EnvSecretStore(System::getenv, Collections.unmodifiableSet(names));
    }

    /** A fixed set of variables (for tests and for hosts that carry their own environment). */
    public static EnvSecretStore of(Map<String, String> variables) {
        Map<String, String> copy = Map.copyOf(variables);
        Set<String> names = new TreeSet<>();
        for (String n : copy.keySet()) if (SecretNames.isValid(n)) names.add(n);
        return new EnvSecretStore(copy::get, Collections.unmodifiableSet(names));
    }

    /** Any lookup function; {@link #names()} is then empty because a function cannot be enumerated. */
    public static EnvSecretStore of(Function<String, String> lookup) {
        return new EnvSecretStore(lookup, Set.of());
    }

    @Override
    public String resolve(String name) {
        String v = name == null ? null : env.apply(name);
        if (v == null || v.isBlank()) throw new SecretNotFoundException(String.valueOf(name));
        return v;
    }

    @Override
    public boolean contains(String name) {
        if (name == null) return false;
        String v = env.apply(name);
        return v != null && !v.isBlank();
    }

    @Override
    public Set<String> names() {
        return names;
    }

    @Override
    public Optional<SecretMetadata> metadata(String name) {
        return contains(name) ? Optional.of(SecretMetadata.NONE) : Optional.empty();
    }
}
