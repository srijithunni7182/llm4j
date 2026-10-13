package io.github.llm4j.secret;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * What a run knows about whoever started it: the credentials a tool needs in order to act <em>as that person</em> (an OAuth access token, for
 * example). The host implements it and hands it to the run; a script refers to a credential as {@code invoker.NAME}.
 *
 * <p>Nothing is kept by the caller of {@link #get}: it asks again for <em>every</em> request that needs the value. That is what lets the
 * implementation refresh an expiring token: return the cached access token while it is valid and, once it is not, use the refresh token to
 * obtain a new one.
 *
 * <p>Implementations must be thread-safe and quick on the common path; they are called from any thread of the run, possibly in parallel.
 * They must not put a credential value in an exception message or in {@link #toString()}.
 */
public interface InvokerCredentials {

    /**
     * The current value of the credential called {@code name}, for example {@code get("token")} returning {@code "Bearer eyJ…"}.
     *
     * @return the value, or {@code null} if this invoker has no such credential
     * @throws SecretException if there is one but it cannot be obtained (a revoked refresh token, say)
     */
    String get(String name);

    /**
     * The server rejected the value last returned for {@code name} (HTTP 401). Drop any cached copy so that the next {@link #get} fetches a
     * new one. The default does nothing.
     */
    default void invalidate(String name) { }

    /**
     * Whether this object can ever supply {@code name}. A run asks before its first step, so a missing credential stops the run at its start
     * instead of in the middle. A {@code true} that later turns out wrong only fails the tool call. The default is {@code true}.
     */
    default boolean provides(String name) {
        return true;
    }

    /** An invoker with no credentials: a script that needs one cannot be run with it. */
    static InvokerCredentials none() {
        return Fixed.NONE;
    }

    /** Constant values; for tests and for the command line. */
    static InvokerCredentials of(Map<String, String> values) {
        Objects.requireNonNull(values, "values");
        return new Fixed(Map.copyOf(values));
    }

    /** One credential whose value comes from a supplier, asked each time. */
    static InvokerCredentials of(String name, Supplier<String> value) {
        SecretNames.require(name);
        Objects.requireNonNull(value, "value");
        return new Supplied(name, value);
    }

    /** Constant values. {@link #toString()} shows names only. */
    final class Fixed implements InvokerCredentials {
        private static final Fixed NONE = new Fixed(Map.of());
        private final Map<String, String> values;

        private Fixed(Map<String, String> values) {
            this.values = values;
        }

        @Override
        public String get(String name) {
            return values.get(name);
        }

        @Override
        public boolean provides(String name) {
            return values.containsKey(name);
        }

        @Override
        public String toString() {
            return "InvokerCredentials" + Set.copyOf(values.keySet());
        }
    }

    /** One named credential read from a supplier on every call. */
    final class Supplied implements InvokerCredentials {
        private final String name;
        private final Supplier<String> value;

        private Supplied(String name, Supplier<String> value) {
            this.name = name;
            this.value = value;
        }

        @Override
        public String get(String requested) {
            return name.equals(requested) ? value.get() : null;
        }

        @Override
        public boolean provides(String requested) {
            return name.equals(requested);
        }

        @Override
        public String toString() {
            return "InvokerCredentials[" + name + "]";
        }
    }
}
