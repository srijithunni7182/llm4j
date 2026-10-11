package io.github.llm4j.loom.observe;

import java.io.PrintStream;

/**
 * The hook an observability add-on implements, found with {@link java.util.ServiceLoader}. {@code weave --observe} opens a session
 * once per process and attaches every run it creates to it, before the run starts.
 */
public interface ObserveProvider {

    /** A short name for messages, for example {@code loom-inspect}. */
    String name();

    /** Starts whatever serves the observer (a web page, an exporter). Throws {@link IllegalArgumentException} for options it refuses. */
    Session open(ObserveOptions options, PrintStream out);

    /** One process's worth of observing. */
    interface Session extends AutoCloseable {

        /** Starts watching a run. Call before the run's executor is initialized. */
        void attach(ObservableRun run);

        /** Where to look, in words, for example {@code http://127.0.0.1:8787}. */
        String describe();

        /** Stops serving. Whatever was observed is finished with. */
        @Override
        void close();
    }
}
