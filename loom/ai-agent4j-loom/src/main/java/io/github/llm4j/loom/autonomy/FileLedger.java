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
    /** File locks keep processes apart; this keeps the threads of one process apart (a lock cannot be held twice in a JVM). */
    private static final Object JVM = new Object();
    private final Path dir;

    /** What this instance has read of one decision's file: the records so far and how far into the file they reach. */
    private static final class State {
        final LedgerCache cache = new LedgerCache();
        long offset;
        int unreadable;
    }

    private final Map<String, State> states = new java.util.HashMap<>();

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

    private State state(String decision) {
        return states.computeIfAbsent(decision, d -> new State());
    }

    /** Reads what was appended since this instance last looked (by anyone), and nothing it has read before. */
    private State refresh(FileChannel channel, String decision) throws IOException {
        State st = state(decision);
        long size = channel.size();
        if (size < st.offset) {
            st.cache.clear();
            st.offset = 0;
        }
        if (size > st.offset) {
            byte[] bytes = new byte[(int) (size - st.offset)];
            channel.read(java.nio.ByteBuffer.wrap(bytes), st.offset);
            int end = bytes.length;
            while (end > 0 && bytes[end - 1] != '\n') end--;
            String text = new String(bytes, 0, end, StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                if (line.isBlank()) continue;
                try {
                    st.cache.add(fromMap(JSON.readValue(line, new TypeReference<Map<String, Object>>() { })));
                } catch (JsonProcessingException | RuntimeException e) {
                    st.unreadable++;
                }
            }
            st.offset += end;
        }
        return st;
    }

    @Override
    public void append(Rec record) {
        synchronized (JVM) {
            Path file = file(record.decision());
            try {
                Files.createDirectories(file.getParent());
                try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
                     FileLock lock = channel.lock()) {
                    State st = refresh(channel, record.decision());
                    if (st.cache.has(record.id())) return;
                    repairTail(channel);
                    byte[] line = (JSON.writeValueAsString(toMap(record)) + "\n").getBytes(StandardCharsets.UTF_8);
                    channel.position(channel.size());
                    channel.write(java.nio.ByteBuffer.wrap(line));
                    channel.force(false);
                    refresh(channel, record.decision());
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot append to the ledger " + file, e);
            }
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

    private State read(String decision) {
        Path file = file(decision);
        State st = state(decision);
        if (!Files.exists(file)) return st;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ); FileLock lock = channel.lock(0, Long.MAX_VALUE, true)) {
            State refreshed = refresh(channel, decision);
            refreshed.unreadable = refreshed.unreadable; // lines that failed to parse stay counted
            return refreshed;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the ledger " + file, e);
        }
    }

    @Override
    public List<Rec> records(String decision) {
        synchronized (JVM) {
            return read(decision).cache.records();
        }
    }

    @Override
    public List<Rec> recordsOfKind(String decision, String... kinds) {
        synchronized (JVM) {
            return read(decision).cache.ofKinds(kinds);
        }
    }

    @Override
    public List<Case> cases(String decision) {
        synchronized (JVM) {
            return read(decision).cache.cases();
        }
    }

    @Override
    public int unreadable(String decision) {
        synchronized (JVM) {
            State st = read(decision);
            Path file = file(decision);
            try {
                // a trailing fragment with no newline is a torn write too, until the next append cuts it away
                if (Files.exists(file) && Files.size(file) > st.offset) return st.unreadable + 1;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return st.unreadable;
        }
    }

    @Override
    public void purgeFields(String decision, Instant before) {
        synchronized (JVM) {
            List<Rec> all = records(decision);
            if (all.isEmpty()) return;
            Path file = file(decision);
            Path tmp = file.resolveSibling("ledger.jsonl.tmp");
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE); FileLock lock = channel.lock()) {
                StringBuilder out = new StringBuilder();
                for (Rec r : all) out.append(JSON.writeValueAsString(toMap(Purge.apply(r, before)))).append('\n');
                Files.writeString(tmp, out.toString());
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                states.remove(decision); // read again from the rewritten file
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot purge the ledger " + file, e);
            }
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
