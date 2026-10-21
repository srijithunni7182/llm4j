package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.observe.Observe;
import io.github.llm4j.loom.observe.ObserveOptions;
import io.github.llm4j.loom.observe.ObserveProvider;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine.Option;

/**
 * The {@code --observe*} options, shared by every command that runs workflows ({@code run}, {@code
 * resume}, {@code tick}, {@code daemon}), so a process is observed the same way however it was
 * started.
 */
final class ObserveMixin {

    @Option(
            names = "--observe",
            arity = "0..1",
            fallbackValue = "8787",
            paramLabel = "port",
            description =
                    "Show runs live on a web page (needs the loom-inspect add-on on the class path). Default port 8787.")
    private String observe;

    @Option(
            names = "--observe-bind",
            paramLabel = "address",
            description = "The address the page listens on (default 127.0.0.1).")
    private String observeBind;

    @Option(
            names = "--observe-detail",
            paramLabel = "full|truncated|metadata",
            description =
                    "How much of what agents say and tools return may leave the process (default truncated: 500 characters).")
    private String observeDetail;

    @Option(
            names = "--observe-name",
            paramLabel = "name",
            description =
                    "This process's name on the page or the server (required with --observe-fabric or --observe-otlp).")
    private String observeName;

    @Option(
            names = "--observe-linger",
            paramLabel = "time",
            description =
                    "Keep the page up this long after the run ends, e.g. 5m, so its final state can be read.")
    private String observeLinger;

    @Option(
            names = "--observe-fabric",
            paramLabel = "url",
            description =
                    "Report this process to a Fabric server at this URL (or set LOOM_FABRIC_URL). Needs --observe-name. Events are kept in a local outbox, so a server that is down never affects the run.")
    private String observeFabric;

    @Option(
            names = "--observe-ca",
            paramLabel = "file",
            description =
                    "A PEM file with the public certificate of the CA that signed the server's certificate (or LOOM_FABRIC_CA). Added to the default roots for this connection only.")
    private String observeCa;

    @Option(
            names = "--observe-ca-only",
            description = "Trust only the CA given with --observe-ca, not the default roots.")
    private boolean observeCaOnly;

    @Option(
            names = "--observe-pin",
            paramLabel = "sha256:hex",
            description =
                    "Accept only a server whose public key has this SHA-256 (or LOOM_FABRIC_PIN). Printed by the server at start-up.")
    private String observePin;

    @Option(
            names = "--observe-outbox",
            paramLabel = "dir",
            description = "Where the local outbox is kept (default ./loom-inspect-outbox).")
    private String observeOutbox;

    @Option(
            names = "--observe-key-secret",
            paramLabel = "name",
            description =
                    "The secret holding this process's key for the server (default LOOM_FABRIC_CLIENT_KEY).")
    private String observeKeySecret;

    @Option(
            names = "--observe-flush-timeout",
            paramLabel = "time",
            description = "At exit, try this long to deliver what is left (default 5s).")
    private String observeFlushTimeout;

    @Option(
            names = "--observe-otlp",
            paramLabel = "url",
            description =
                    "Also export traces and logs to any OpenTelemetry endpoint (OTLP over HTTP), for example https://collector.example.com:4318. Needs --observe-name.")
    private String observeOtlp;

    @Option(
            names = "--observe-rules",
            paramLabel = "file",
            description =
                    "What counts as trouble for each agent, kept beside the workflow and not in it (default: <script>.inspect next to the script, when there is one).")
    private String observeRules;

    @Option(
            names = "--observe-otlp-header",
            paramLabel = "Name=value",
            description =
                    "A header for the OTLP endpoint; a value may be ${secret:NAME}, read from the secret store. Repeatable.")
    private List<String> observeOtlpHeaders = new ArrayList<>();

    /**
     * The options as parsed, or null when none asks for anything. Throws {@link
     * IllegalArgumentException} for a bad one.
     */
    ObserveOptions parsed() {
        return ObserveOptions.fromCommandLine(
                observe,
                observeBind,
                observeDetail,
                observeName,
                observeLinger,
                observeFabric,
                observeCa,
                observeCaOnly,
                observePin,
                observeOutbox,
                observeKeySecret,
                observeFlushTimeout,
                observeOtlp,
                observeOtlpHeaders,
                System::getenv);
    }

    String name() {
        return observeName;
    }

    java.nio.file.Path outbox() {
        return observeOutbox == null || observeOutbox.isBlank()
                ? null
                : java.nio.file.Path.of(observeOutbox.trim());
    }

    /**
     * What opening found: the environment to run with, the session to close afterwards, or a
     * failure already reported.
     */
    record Opened(
            WeaveEnv env, ObserveProvider.Session session, ObserveOptions options, boolean failed) {

        /** For a command that ends: keeps the page up for the linger time, then closes. */
        void finish() {
            if (session != null)
                WeaveCLI.RunCommand.lingerThenClose(session, options.linger(), env);
        }

        /**
         * For a command that runs until it is stopped (a daemon): closes the session, which flushes
         * the outbox, on the way out.
         */
        void closeAtExit() {
            if (session != null)
                Runtime.getRuntime()
                        .addShutdownHook(new Thread(session::close, "loom-observe-close"));
        }
    }

    /**
     * Opens what the options ask for (or nothing), reports a bad option or a missing add-on, and
     * attaches the session to {@code env}.
     */
    Opened open(WeaveEnv env) {
        ObserveOptions observing;
        try {
            observing =
                    ObserveOptions.fromCommandLine(
                            observe,
                            observeBind,
                            observeDetail,
                            observeName,
                            observeLinger,
                            observeFabric,
                            observeCa,
                            observeCaOnly,
                            observePin,
                            observeOutbox,
                            observeKeySecret,
                            observeFlushTimeout,
                            observeOtlp,
                            observeOtlpHeaders,
                            System::getenv);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            return new Opened(env, null, null, true);
        }
        if (observing == null) return new Opened(env, null, null, false);
        if (observeRules != null && !observeRules.isBlank()) {
            java.nio.file.Path rules = java.nio.file.Path.of(observeRules.trim());
            if (!java.nio.file.Files.isRegularFile(rules)) {
                System.err.println("Error: --observe-rules: no such file: " + observeRules);
                return new Opened(env, null, null, true);
            }
            observing = observing.withRules(rules);
        }
        try {
            ObserveProvider.Session session = Observe.open(observing, env.out());
            env.out().println("🔭 " + session.summary());
            return new Opened(env.withObserve(session), session, observing, false);
        } catch (IllegalArgumentException | IllegalStateException | Observe.Unavailable e) {
            System.err.println("Error: " + e.getMessage());
            return new Opened(env, null, null, true);
        }
    }
}
