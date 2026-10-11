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

    /**
     * Delivers what an earlier process left in its local outbox, by hand ({@code weave observe flush}). Returns how many records are still
     * undelivered afterwards. A provider with no outbox says so.
     */
    default long flush(ObserveOptions options, io.github.llm4j.secret.SecretStore secrets, PrintStream out) {
        throw new UnsupportedOperationException(name() + " keeps no outbox");
    }

    /** Deletes the local outbox of {@code name} (under {@code outbox}, or the default place) that no running process is using ({@code weave observe purge}). Returns how many records went. */
    default long purge(String name, java.nio.file.Path outbox, PrintStream out) {
        throw new UnsupportedOperationException(name() + " keeps no outbox");
    }

    /**
     * Checks a rules file against a script without running anything ({@code weave observe rules}). Prints what it finds and returns the
     * number of errors.
     */
    default int checkRules(io.github.llm4j.loom.ast.LoomScript script, java.nio.file.Path rulesFile, PrintStream out) {
        throw new UnsupportedOperationException(name() + " has no rules to check");
    }

    /** One process's worth of observing. */
    interface Session extends AutoCloseable {

        /** Starts watching a run. Call before the run's executor is initialized. */
        void attach(ObservableRun run);

        /**
         * As {@link #attach(ObservableRun)}, saying which script file the run was loaded from, so a rules file kept beside it
         * ({@code flow.inspect} next to {@code flow.loom}) can be found.
         */
        default void attach(ObservableRun run, java.nio.file.Path scriptFile) {
            attach(run);
        }

        /** Where to look, in words, for example {@code http://127.0.0.1:8787}. */
        String describe();

        /** One line for the console when the session has opened, for example {@code Watching this run at http://127.0.0.1:8787}. */
        default String summary() {
            return "Watching this run at " + describe();
        }

        /** Stops serving. Whatever was observed is finished with. */
        @Override
        void close();
    }
}
