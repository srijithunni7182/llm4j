package io.github.llm4j.examples.expensedesk;

import io.github.llm4j.loom.execution.DefaultLLMClientFactory;
import io.github.llm4j.loom.execution.LLMClientFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

/**
 * Starts the expense desk.
 *
 * <pre>
 * ExpenseDesk [--port 8787] [--data var/expense-desk] [--script expense.loom] [--renderer FILE] [--live]
 * </pre>
 *
 * <p>Without {@code --live} the models are {@link OfflineModels}: no key, no network. With it, the
 * script's models are real ones and their keys come from the environment.
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
        Desk desk = new Desk(script, data, models, Clock.systemUTC());
        // Loopback only: the desk has no sign-in, so it is not offered to the network.
        InetSocketAddress address = new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
        DeskServer server = new DeskServer(desk, address, live ? "live" : "offline", read(renderer));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            desk.close();
        }));
        System.out.println("Expense Desk is at http://localhost:" + server.port() + " (" + (live ? "live" : "offline") + " models)");
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
