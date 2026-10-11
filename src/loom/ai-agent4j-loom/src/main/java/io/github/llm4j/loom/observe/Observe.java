package io.github.llm4j.loom.observe;

import java.io.PrintStream;
import java.util.ServiceLoader;

/** Finds an {@link ObserveProvider} on the class path and opens a session with it. */
public final class Observe {

    /** No add-on is on the class path. The message says what to do. */
    public static final class Unavailable extends Exception {
        public Unavailable(String message) {
            super(message);
        }
    }

    private Observe() { }

    /** Opens a session with the first provider found by {@link ServiceLoader}. */
    public static ObserveProvider.Session open(ObserveOptions options, PrintStream out) throws Unavailable {
        return open(options, out, ServiceLoader.load(ObserveProvider.class));
    }

    /** Opens a session with the first of {@code providers}. */
    public static ObserveProvider.Session open(ObserveOptions options, PrintStream out, Iterable<ObserveProvider> providers) throws Unavailable {
        for (ObserveProvider p : providers) return p.open(options, out);
        throw new Unavailable("--observe needs the loom-inspect add-on on the class path (add the loom-inspect jar next to weave). Nothing was run.");
    }
}
