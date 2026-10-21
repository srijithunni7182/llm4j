package io.github.llm4j.loom.fabric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * What the server has accepted, kept on disk so a restart loses nothing: JSON lines in one file per
 * client per day ({@code history/<client>/2026-10-11.jsonl}), no database to install. A batch is
 * appended and forced to disk before the server acknowledges it, so an acknowledged record is never
 * lost; a line cut off by a crash is skipped when the history is read back. Retention is by age and
 * by total size, oldest day first.
 */
public final class History {

    /** One stored message. */
    public record Entry(String client, String kind, Instant at, String body) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path root;
    private final Clock clock;

    public History(Path dataDir, Clock clock) {
        this.root = dataDir.resolve("history");
        this.clock = clock;
    }

    /**
     * Appends and forces to disk. {@code client} must be a validated name (letters, digits, '.',
     * '_', '-').
     */
    public synchronized void append(String client, String kind, String body) {
        Instant now = clock.instant();
        Path dir = root.resolve(client);
        Path file = dir.resolve(LocalDate.ofInstant(now, ZoneOffset.UTC) + ".jsonl");
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("k", kind);
        line.put("t", now.toString());
        line.put("b", body);
        try {
            Files.createDirectories(dir);
            byte[] bytes = (JSON.writeValueAsString(line) + "\n").getBytes(StandardCharsets.UTF_8);
            try (FileChannel ch =
                    FileChannel.open(
                            file,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.APPEND)) {
                ch.write(ByteBuffer.wrap(bytes));
                ch.force(false);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every stored message, each client's in the order it was written. */
    public void replay(Consumer<Entry> sink) {
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> clients = Files.list(root)) {
            for (Path c : clients.filter(Files::isDirectory).sorted().toList()) {
                try (Stream<Path> days = Files.list(c)) {
                    for (Path day :
                            days.filter(p -> p.toString().endsWith(".jsonl")).sorted().toList()) {
                        for (String line : Files.readAllLines(day, StandardCharsets.UTF_8)) {
                            Entry e = parse(c.getFileName().toString(), line);
                            if (e != null) sink.accept(e);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Entry parse(String client, String line) {
        try {
            JsonNode n = JSON.readTree(line);
            if (n == null || !n.hasNonNull("k") || !n.hasNonNull("b")) return null;
            return new Entry(
                    client,
                    n.get("k").asText(),
                    Instant.parse(n.get("t").asText()),
                    n.get("b").asText());
        } catch (IOException | RuntimeException e) {
            return null; // a line a crash cut off
        }
    }

    /**
     * Deletes day files older than {@code days}, then the oldest days until the history fits in
     * {@code maxBytes}. Returns how many files went.
     */
    public synchronized int enforce(int days, long maxBytes) {
        if (!Files.isDirectory(root)) return 0;
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        List<Path> files = new ArrayList<>();
        try (Stream<Path> all = Files.walk(root)) {
            all.filter(p -> p.toString().endsWith(".jsonl")).forEach(files::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        files.sort(
                Comparator.comparing((Path p) -> p.getFileName().toString())
                        .thenComparing(Path::toString));
        int removed = 0;
        long total = 0;
        List<Path> kept = new ArrayList<>();
        try {
            for (Path f : files) {
                LocalDate day;
                try {
                    day = LocalDate.parse(f.getFileName().toString().replace(".jsonl", ""));
                } catch (RuntimeException e) {
                    continue; // not ours
                }
                if (day.isBefore(today.minusDays(days))) {
                    Files.delete(f);
                    removed++;
                } else {
                    kept.add(f);
                    total += Files.size(f);
                }
            }
            for (Path f : kept) {
                if (total <= maxBytes) break;
                total -= Files.size(f);
                Files.delete(f);
                removed++;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return removed;
    }
}
