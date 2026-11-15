package io.github.llm4j.examples.expensedesk;

import io.github.llm4j.loom.execution.DefaultLLMClientFactory;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.host.LoomHost;
import io.github.llm4j.loom.observe.Observe;
import io.github.llm4j.loom.observe.ObserveOptions;
import io.github.llm4j.loom.observe.ObserveProvider;
import io.github.llm4j.secret.EncryptedFileSecretStore;
import io.github.llm4j.secret.MasterKey;
import io.github.llm4j.secret.SecretStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * Starts the expense desk.
 *
 * <pre>
 * ExpenseDesk [--port 8787] [--data var/expense-desk] [--script expense.loom] [--renderer FILE] [--live]
 *             [--fabric URL --name NAME [--secrets .loom/secrets.store] [--secrets-key-file FILE]]
 * </pre>
 *
 * <p>Without {@code --live} the models are {@link OfflineModels}: no key, no network. With it, the
 * script's models are real ones and their keys come from the environment.
 *
 * <p>With {@code --fabric} every run is reported to a Fabric server under {@code --name}. The
 * client key is read from the encrypted secret store (the secret {@code LOOM_FABRIC_CLIENT_KEY}),
 * never from an option, and the loom-inspect add-on must be on the class path.
 */
public final class ExpenseDesk {

    private static final int DEFAULT_PORT = 8787;

    private ExpenseDesk() {}

    public static void main(String[] args) {
        List<String> options = List.of(args);
        int port = Integer.parseInt(option(options, "--port", String.valueOf(DEFAULT_PORT)));
        Path script = Path.of(option(options, "--script", "expense.loom"));
        Path data = Path.of(option(options, "--data", "var/expense-desk"));
        Path renderer = Path.of(option(options, "--renderer", "../../loom/graph-render/graph-render.js"));
        boolean live = options.contains("--live");

        LLMClientFactory models = live ? new DefaultLLMClientFactory() : new OfflineModels();
        Optional<Watch> watch = Watch.from(options, script);
        Desk desk = new Desk(script, data, models, Clock.systemUTC(), builder -> watch.map(w -> w.on(builder)).orElse(builder));
        // Loopback only: the desk has no sign-in, so it is not offered to the network.
        InetSocketAddress address = new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
        DeskServer server = new DeskServer(desk, address, live ? "live" : "offline", read(renderer));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            desk.close();
            watch.ifPresent(Watch::close); // last: closing delivers what the runs reported
        }));
        watch.ifPresent(w -> System.out.println("Reporting to Fabric: " + w.summary()));
        System.out.println("Expense Desk is at http://localhost:" + server.port() + " (" + (live ? "live" : "offline") + " models)");
    }

    /** A session with a Fabric server, and the store its client key is read from. */
    private record Watch(ObserveProvider.Session session, SecretStore secrets) {

        static Optional<Watch> from(List<String> options, Path script) {
            String url = option(options, "--fabric", "");
            if (url.isEmpty()) return Optional.empty();
            String name = option(options, "--name", "");
            if (name.isEmpty()) throw new IllegalArgumentException("--fabric needs --name: how this process is shown on the server");
            Path store = Path.of(option(options, "--secrets", ".loom/secrets.store"));
            String keyFile = store.toString().replaceFirst("\\.store$", "") + ".key";
            SecretStore secrets = EncryptedFileSecretStore.open(store, MasterKey.fromFile(Path.of(option(options, "--secrets-key-file", keyFile))));
            ObserveOptions observing =
                    ObserveOptions.fromCommandLine(null, null, null, name, null, url, null, false, null, null, null, null, System::getenv);
            try {
                return Optional.of(new Watch(Observe.open(observing, System.out), secrets));
            } catch (Observe.Unavailable e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }

        LoomHost.Builder on(LoomHost.Builder builder) {
            return builder.secretStore(secrets).observe(session);
        }

        String summary() {
            return session.summary();
        }

        void close() {
            session.close();
        }
    }

    private static String option(List<String> options, String name, String fallback) {
        int at = options.indexOf(name);
        if (at < 0) return fallback;
        if (at + 1 >= options.size()) throw new IllegalArgumentException(name + " needs a value");
        return options.get(at + 1);
    }

    /** The graph renderer's source, or empty when this is not a checkout that has it. */
    private static String read(Path renderer) {
        if (!Files.isRegularFile(renderer)) return "";
        try {
            return Files.readString(renderer);
        } catch (IOException e) {
            throw new UncheckedIOException(renderer + " could not be read", e);
        }
    }
}
