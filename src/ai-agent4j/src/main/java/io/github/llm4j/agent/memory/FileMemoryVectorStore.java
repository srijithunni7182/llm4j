package io.github.llm4j.agent.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An {@link InMemoryVectorStore} kept in a JSON file, so an agent's long-term facts survive restarts.
 * Loaded when constructed; rewritten (atomically) after every change. Suited to the small stores of
 * per-user facts, not to large corpora.
 */
public class FileMemoryVectorStore extends InMemoryVectorStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;
    private final Map<String, Stored> entries = new LinkedHashMap<>();

    /** One stored vector, as written to the file. */
    public static final class Stored {
        public String id;
        public float[] embedding;
        public Map<String, Object> metadata;

        public Stored() {}

        Stored(String id, float[] embedding, Map<String, Object> metadata) {
            this.id = id;
            this.embedding = embedding;
            this.metadata = metadata;
        }
    }

    /**
     * @throws UncheckedIOException if the file exists but can't be read as a store
     */
    public FileMemoryVectorStore(Path file) {
        this.file = file;
        if (Files.exists(file)) {
            try {
                List<Stored> loaded = MAPPER.readValue(file.toFile(), new TypeReference<List<Stored>>() {});
                for (Stored s : loaded) {
                    super.add(s.id, s.embedding, s.metadata);
                    entries.put(s.id, s);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Can't read memory store " + file + ": " + e.getMessage(), e);
            }
        }
    }

    @Override
    public synchronized void add(String id, float[] embedding, Map<String, Object> metadata) {
        super.add(id, embedding, metadata);
        entries.put(id, new Stored(id, embedding, metadata != null ? new HashMap<>(metadata) : new HashMap<>()));
        save();
    }

    @Override
    public synchronized boolean delete(String id) {
        boolean removed = super.delete(id);
        if (removed) {
            entries.remove(id);
            save();
        }
        return removed;
    }

    @Override
    public synchronized void clear() {
        super.clear();
        entries.clear();
        save();
    }

    public Path getFile() {
        return file;
    }

    private void save() {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
            MAPPER.writeValue(tmp.toFile(), new ArrayList<>(entries.values()));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Can't write memory store " + file + ": " + e.getMessage(), e);
        }
    }
}
