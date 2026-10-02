package io.github.llm4j.loom.autonomy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One JSON-lines file per decision, {@code <dir>/<decision>/ledger.jsonl}, appended under a file lock so two processes never interleave a
 * line. A torn last line (a crash mid-write) is ignored on read, counted for {@code status}, and repaired by the next append.
 */
public class FileLedger implements Ledger {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Object JVM = new Object();
    private final Path dir;
    private final Map<String, Set<String>> known = new HashMap<>();
    private final Map<String, Integer> torn = new HashMap<>();

    public FileLedger(Path dir) {
        this.dir = dir;
    }

    /** The directory the store keeps its files in. */
    public Path dir() {
        return dir;
    }

    private Path file(String decision) {
        Names.check(decision);
        return dir.resolve(decision).resolve("ledger.jsonl");
    }

    @Override
    public void append(Rec record) {
        synchronized (JVM) { appendLocked(record); }
    }

    private void appendLocked(Rec record) {
        Path file = file(record.decision());
        try {
            Files.createDirectories(file.getParent());
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
                 FileLock lock = channel.lock()) {
                Set<String> ids = known.computeIfAbsent(record.decision(), d -> new HashSet<>());
                if (ids.isEmpty() || !ids.contains(record.id())) {
                    // another process may have written since: read what the file holds now
                    ids.clear();
                    for (Rec r : readAll(channel, record.decision(), false)) ids.add(r.id());
                }
                if (ids.contains(record.id())) return;
                repairTail(channel);
                byte[] line = (JSON.writeValueAsString(toMap(record)) + "\n").getBytes(StandardCharsets.UTF_8);
                channel.position(channel.size());
                channel.write(java.nio.ByteBuffer.wrap(line));
                channel.force(false);
                ids.add(record.id());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot append to the ledger " + file, e);
        }
    }

    /** Drops a partial last line, left by a write that was cut off. */
    private static void repairTail(FileChannel channel) throws IOException {
        long size = channel.size();
        if (size == 0) return;
        java.nio.ByteBuffer last = java.nio.ByteBuffer.allocate(1);
        channel.read(last, size - 1);
        if (last.get(0) == '\n') return;
        java.nio.ByteBuffer all = java.nio.ByteBuffer.allocate((int) size);
        channel.read(all, 0);
        int cut = 0;
        for (int i = (int) size - 1; i >= 0; i--) if (all.get(i) == '\n') { cut = i + 1; break; }
        channel.truncate(cut);
    }

    @Override
    public List<Rec> records(String decision) {
        synchronized (JVM) { return recordsLocked(decision); }
    }

    private List<Rec> recordsLocked(String decision) {
        Path file = file(decision);
        if (!Files.exists(file)) return List.of();
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ); FileLock lock = channel.lock(0, Long.MAX_VALUE, true)) {
            return readAll(channel, decision, true);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the ledger " + file, e);
        }
    }

    private List<Rec> readAll(FileChannel channel, String decision, boolean count) throws IOException {
        long size = channel.size();
        byte[] bytes = new byte[(int) size];
        channel.read(java.nio.ByteBuffer.wrap(bytes), 0);
        String text = new String(bytes, StandardCharsets.UTF_8);
        List<Rec> out = new ArrayList<>();
        int bad = 0;
        for (String line : text.split("\n")) {
            if (line.isBlank()) continue;
            try {
                out.add(fromMap(JSON.readValue(line, new TypeReference<Map<String, Object>>() { })));
            } catch (JsonProcessingException | RuntimeException e) {
                bad++;
            }
        }
        if (count) torn.put(decision, bad);
        return out;
    }

    @Override
    public int unreadable(String decision) {
        synchronized (JVM) {
        recordsLocked(decision);
        return torn.getOrDefault(decision, 0);
        }
    }

    @Override
    public void purgeFields(String decision, Instant before) {
        synchronized (JVM) { purgeLocked(decision, before); }
    }

    private void purgeLocked(String decision, Instant before) {
        List<Rec> all = recordsLocked(decision);
        if (all.isEmpty()) return;
        Path file = file(decision);
        Path tmp = file.resolveSibling("ledger.jsonl.tmp");
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE); FileLock lock = channel.lock()) {
            StringBuilder out = new StringBuilder();
            for (Rec r : all) out.append(JSON.writeValueAsString(toMap(Purge.apply(r, before)))).append('\n');
            Files.writeString(tmp, out.toString());
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot purge the ledger " + file, e);
        }
    }

    static Map<String, Object> toMap(Rec r) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", r.id());
        m.put("decision", r.decision());
        m.put("kind", r.kind());
        m.put("at", r.at().toString());
        m.put("case", r.caseId());
        m.put("generation", r.generation());
        m.put("body", r.body());
        return m;
    }

    @SuppressWarnings("unchecked")
    static Rec fromMap(Map<String, Object> m) {
        return new Rec((String) m.get("id"), (String) m.get("decision"), (String) m.get("kind"), Instant.parse((String) m.get("at")),
                (String) m.get("case"), ((Number) m.get("generation")).intValue(), (Map<String, Object>) m.get("body"));
    }

    static String json(Rec r) {
        try {
            return JSON.writeValueAsString(toMap(r));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    static Rec parse(String text) {
        try {
            return fromMap(JSON.readValue(text, new TypeReference<Map<String, Object>>() { }));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt ledger record", e);
        }
    }
}
