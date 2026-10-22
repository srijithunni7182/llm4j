package io.github.llm4j.loom.inspect.emit;

import io.github.llm4j.loom.inspect.wire.Hello;
import io.github.llm4j.loom.inspect.wire.Wire;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The outbox by hand: deliver what a process that is gone left behind ({@code weave observe
 * flush}), or throw it away ({@code weave observe purge}). Neither touches a directory a running
 * process holds.
 */
public final class OutboxTools {

    private static final long SEGMENT = 8L * 1024 * 1024;
    private static final long CAP_BYTES = 256L * 1024 * 1024;
    private static final Duration CAP_AGE = Duration.ofDays(7);

    private OutboxTools() {}

    /** The directories of {@code name} under {@code root} that no running process holds. */
    private static List<Path> idle(Path root, String name) {
        Path dir = root.resolve(name);
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (var list = Files.list(dir)) {
            list.filter(Files::isDirectory)
                    .filter(d -> !Outbox.inUse(d))
                    .sorted(Comparator.comparingLong(Outbox::lastWrite))
                    .forEach(out::add);
        } catch (IOException e) {
            // nothing to find
        }
        return out;
    }

    /**
     * Sends everything the idle outboxes of {@code name} hold, oldest first, removing each once it
     * is empty. Gives up after {@code timeout}. Returns how many records are still undelivered.
     */
    public static long flush(
            String name,
            Path root,
            Transport transport,
            Duration timeout,
            Clock clock,
            PrintStream out,
            ServerLink.Timing timing) {
        List<Path> dirs = idle(root, name);
        if (dirs.isEmpty()) {
            out.println(
                    "Nothing to flush: no outbox of '"
                            + name
                            + "' under "
                            + root.toAbsolutePath()
                            + ".");
            return 0;
        }
        List<ServerLink.Source> sources = new ArrayList<>();
        for (Path d : dirs) {
            try {
                Outbox o = new Outbox(d, SEGMENT, CAP_BYTES, CAP_AGE, clock);
                sources.add(new ServerLink.Source(o, new Cursor(d, "fabric"), true));
            } catch (IllegalStateException e) {
                // taken between listing and opening: leave it
            }
        }
        if (sources.isEmpty()) return 0;
        ServerLink.Source newest =
                sources.get(
                        sources.size()
                                - 1); // the link drains its "own" log last, so the newest goes
        // there
        Hello hello =
                new Hello(
                        name,
                        "flush",
                        "",
                        ProcessHandle.current().pid(),
                        "",
                        "",
                        Wire.VERSION,
                        List.of());
        ServerLink link =
                new ServerLink(
                        name,
                        transport,
                        newest,
                        sources.subList(0, sources.size() - 1),
                        hello,
                        Map::of,
                        timing,
                        clock,
                        out);
        long before = link.backlog();
        link.start();
        long end = System.currentTimeMillis() + timeout.toMillis();
        while (!link.dead() && link.backlog() > 0 && System.currentTimeMillis() < end) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        long remaining = link.backlog();
        link.close();
        for (ServerLink.Source s : sources) {
            try {
                s.outbox().close();
            } catch (RuntimeException ignored) {
                // already closed when it was emptied and removed
            }
        }
        out.println(
                "Delivered "
                        + Math.max(0, before - remaining)
                        + " record(s); "
                        + remaining
                        + " remain"
                        + (link.dead() ? " (the server refused this process's key)" : "")
                        + ".");
        return remaining;
    }

    /** Deletes the idle outboxes of {@code name}. Returns how many records were in them. */
    public static long purge(String name, Path root, Clock clock, PrintStream out) {
        List<Path> dirs = idle(root, name);
        long records = 0;
        for (Path d : dirs) {
            try (Outbox o = new Outbox(d, SEGMENT, CAP_BYTES, CAP_AGE, clock)) {
                Cursor c = new Cursor(d, "fabric");
                records += Math.max(0, o.nextOffset() - 1 - c.get());
            } catch (IllegalStateException e) {
                continue; // taken by a running process: leave it
            }
            deleteTree(d);
        }
        out.println(
                "Removed "
                        + dirs.size()
                        + " outbox(es) of '"
                        + name
                        + "' ("
                        + records
                        + " undelivered record(s)).");
        return records;
    }

    private static void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // best effort
        }
    }
}
