package io.github.llm4j.loom.serve;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Answers kept by what they were made from: the request (method and options) and the content of
 * every file the answer read. A change to any of those files, or to a buffer, makes the entry stale
 * the next time it is asked for; nothing else does. Least recently used entries go first once the
 * answers together exceed the memory bound.
 */
public final class ResultCache {

    /** What an answer was made from: each file and the hash of what it held (or of its absence). */
    public record Entry(JsonNode value, Map<Path, String> dependencies, int size) {}

    private final long maxBytes;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private long bytes;
    private int hits;
    private int misses;

    public ResultCache(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    /**
     * The kept answer when every file it read is as it was, using {@code texts} for open buffers.
     */
    public synchronized Optional<JsonNode> get(String key, Map<Path, String> texts) {
        Entry e = entries.get(key);
        if (e != null) {
            for (Map.Entry<Path, String> dep : e.dependencies().entrySet()) {
                if (!dep.getValue().equals(hash(dep.getKey(), texts))) {
                    drop(key);
                    e = null;
                    break;
                }
            }
        }
        if (e == null) {
            misses++;
            return Optional.empty();
        }
        hits++;
        return Optional.of(e.value());
    }

    public synchronized void put(
            String key, JsonNode value, Iterable<Path> read, Map<Path, String> texts) {
        drop(key);
        Map<Path, String> deps = new java.util.LinkedHashMap<>();
        for (Path p : read) deps.put(p, hash(p, texts));
        int size = value.toString().length() * 2;
        entries.put(key, new Entry(value, deps, size));
        bytes += size;
        Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
        while (bytes > maxBytes && it.hasNext()) {
            Map.Entry<String, Entry> oldest = it.next();
            if (oldest.getKey().equals(key) && entries.size() == 1) break;
            bytes -= oldest.getValue().size();
            it.remove();
        }
    }

    public synchronized int hits() {
        return hits;
    }

    public synchronized int misses() {
        return misses;
    }

    public synchronized int entries() {
        return entries.size();
    }

    public synchronized long bytes() {
        return bytes;
    }

    private void drop(String key) {
        Entry old = entries.remove(key);
        if (old != null) bytes -= old.size();
    }

    /**
     * A digest of the file's text (or buffer's), or "absent". For a folder, a digest of the names
     * and contents of every file under it, so a file added or removed changes it.
     */
    static String hash(Path file, Map<Path, String> texts) {
        try {
            if (Files.isDirectory(file)) {
                MessageDigest all = MessageDigest.getInstance("SHA-256");
                try (java.util.stream.Stream<Path> s = Files.walk(file)) {
                    for (Path p : s.filter(Files::isRegularFile).sorted().toList()) {
                        all.update(file.relativize(p).toString().getBytes(StandardCharsets.UTF_8));
                        all.update(Files.readAllBytes(p));
                    }
                }
                return HexFormat.of().formatHex(all.digest());
            }
            String text = texts.get(file);
            byte[] data =
                    text != null
                            ? text.getBytes(StandardCharsets.UTF_8)
                            : Files.exists(file) ? Files.readAllBytes(file) : null;
            if (data == null) return "absent";
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            return "unreadable";
        }
    }
}
