package io.github.llm4j.loom.inspect.emit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.inspect.wire.Command;
import io.github.llm4j.loom.inspect.wire.CommandResult;
import io.github.llm4j.loom.inspect.wire.Wire;
import java.io.IOException;
import java.io.PrintStream;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

/**
 * Asks the server for this process's commands (a long-poll the server answers when one arrives or after 25 s), checks each is addressed
 * to this process, still fresh and not seen before, hands it to the {@code handler} and tells the server what came of it. The answer to
 * the poll must be signed by the server with this process's key, so a man in the middle cannot make a run stop. A failure waits and tries again.
 */
public final class CommandPoller implements AutoCloseable {

    static final String POLL = "/loom-inspect/v1/commands";
    static final String RESULT = "/loom-inspect/v1/command-result";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String name;
    private final Transport transport;
    private final Function<Command, CommandResult> handler;
    private final Clock clock;
    private final PrintStream out;
    private final long waitSeconds;
    private final long retryMillis;
    private final Set<String> seen = new HashSet<>();
    private final Deque<String> seenOrder = new ArrayDeque<>();
    private final Thread thread;
    private volatile boolean closed;

    public CommandPoller(String name, Transport transport, Function<Command, CommandResult> handler, Clock clock, PrintStream out, long waitSeconds, long retryMillis) {
        this.name = name;
        this.transport = transport;
        this.handler = handler;
        this.clock = clock;
        this.out = out;
        this.waitSeconds = waitSeconds;
        this.retryMillis = retryMillis;
        this.thread = new Thread(this::loop, "loom-inspect-commands");
        thread.setDaemon(true);
    }

    public void start() {
        thread.start();
    }

    private void loop() {
        long backoff = retryMillis;
        while (!closed) {
            try {
                Transport.Reply r = transport.postVerified(POLL, "{\"wait\":" + waitSeconds + "}");
                if (!r.ok()) {
                    if (r.status() == 401 || r.status() == 403) return; // the link reports a bad key; commands are not worth a second complaint
                    sleep(backoff);
                    backoff = Math.min(backoff * 2, 30_000);
                    continue;
                }
                backoff = retryMillis;
                handle(r.body());
            } catch (IOException e) {
                if (closed) return;
                sleep(backoff);
                backoff = Math.min(backoff * 2, 30_000);
            } catch (RuntimeException e) {
                if (closed) return;
                out.println("loom-inspect: command channel error: " + e.getMessage());
                sleep(backoff);
            }
        }
    }

    /** Carries out the commands in one answer. Package-private for tests. */
    void handle(String body) {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (IOException e) {
            return;
        }
        if (!name.equals(root.path("client").asText())) return; // an answer meant for another process
        for (JsonNode n : root.path("commands")) {
            Command c;
            try {
                c = Wire.read(n.toString(), Command.class);
            } catch (IllegalArgumentException e) {
                continue;
            }
            report(execute(c));
        }
    }

    private CommandResult execute(Command c) {
        if (c.id() == null || !seen.add(c.id())) return new CommandResult(c.id(), false, "already handled");
        seenOrder.add(c.id());
        if (seenOrder.size() > 200) seen.remove(seenOrder.poll());
        try {
            if (c.expires() == null || Instant.parse(c.expires()).isBefore(clock.instant())) return new CommandResult(c.id(), false, "the command had expired and was not carried out");
            return handler.apply(c);
        } catch (RuntimeException e) {
            return new CommandResult(c.id(), false, "could not carry it out: " + e.getMessage());
        }
    }

    private void report(CommandResult r) {
        try {
            transport.post(RESULT, Wire.write(r));
        } catch (IOException | RuntimeException e) {
            // the run has already done it; the server just does not learn the words
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        closed = true;
        thread.interrupt();
    }
}
