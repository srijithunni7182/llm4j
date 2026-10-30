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

    private Observe() {}

    /** The first provider on the class path. */
    public static ObserveProvider provider() throws Unavailable {
        for (ObserveProvider p : ServiceLoader.load(ObserveProvider.class)) return p;
        throw new Unavailable(
                "the inspect add-on is not on the class path: `weave tools fetch inspect` gets it, or add loom-inspect to --classes.");
    }

    /** Opens a session with the first provider found by {@link ServiceLoader}. */
    public static ObserveProvider.Session open(ObserveOptions options, PrintStream out)
            throws Unavailable {
        return open(options, out, ServiceLoader.load(ObserveProvider.class));
    }

    /** Opens a session with the first of {@code providers}. */
    public static ObserveProvider.Session open(
            ObserveOptions options, PrintStream out, Iterable<ObserveProvider> providers)
            throws Unavailable {
        for (ObserveProvider p : providers) return p.open(options, out);
        throw new Unavailable(
                "--observe needs the inspect add-on, which is not on the class path: `weave tools fetch inspect` gets it, or add loom-inspect to --classes. Nothing was run.");
    }
}
