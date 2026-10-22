package io.github.llm4j.loom.inspect.receive;

import io.github.llm4j.loom.inspect.wire.Command;
import io.github.llm4j.loom.inspect.wire.CommandResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The commands waiting for each process. A process asks for its commands with a long-poll; a
 * command not collected within its lifetime (60 seconds) is dropped, so a process that was offline
 * never wakes up to an old "stop". Results are kept briefly for the audit and the page.
 */
public final class CommandQueue {

    private final Clock clock;
    private final Map<String, LinkedBlockingQueue<Command>> queues = new ConcurrentHashMap<>();
    private final Map<String, CommandResult> results = new ConcurrentHashMap<>();

    public CommandQueue(Clock clock) {
        this.clock = clock;
    }

    /** Queues a command for {@code client}. */
    public Command offer(String client, String trace, String action, String by) {
        Instant now = clock.instant();
        Command c =
                new Command(
                        UUID.randomUUID().toString(),
                        trace,
                        action,
                        by,
                        now.toString(),
                        now.plusSeconds(Command.TTL_SECONDS).toString());
        queues.computeIfAbsent(client, k -> new LinkedBlockingQueue<>()).add(c);
        return c;
    }

    /**
     * Waits up to {@code wait} for a command for {@code client}; returns what is there (possibly
     * nothing), never an expired one.
     */
    public List<Command> next(String client, Duration wait) throws InterruptedException {
        LinkedBlockingQueue<Command> q =
                queues.computeIfAbsent(client, k -> new LinkedBlockingQueue<>());
        List<Command> out = new ArrayList<>();
        Command first = q.poll(wait.toMillis(), TimeUnit.MILLISECONDS);
        if (first != null) out.add(first);
        q.drainTo(out);
        out.removeIf(c -> Instant.parse(c.expires()).isBefore(clock.instant()));
        return out;
    }

    public int waiting(String client) {
        LinkedBlockingQueue<Command> q = queues.get(client);
        return q == null ? 0 : q.size();
    }

    public void result(CommandResult r) {
        if (r.id() == null) return;
        if (results.size() > 1000)
            results.clear(); // results are a courtesy, not a record; the audit log is the record
        results.put(r.id(), r);
    }

    public CommandResult result(String id) {
        return results.get(id);
    }
}
