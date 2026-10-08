package io.github.llm4j.loom.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** A run journal kept in one JSON file — durable across restarts on a single machine. */
public class FileRunJournal implements RunJournal {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path file;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public FileRunJournal(Path file) {
        this.file = file;
        if (Files.exists(file)) {
            try {
                entries.putAll(JSON.readValue(file.toFile(), new TypeReference<Map<String, Entry>>() { }));
            } catch (IOException e) {
                throw new UncheckedIOException("Unreadable run journal " + file, e);
            }
        }
    }

    /** The file the journal is kept in. */
    public Path path() {
        return file;
    }

    @Override
    public synchronized Optional<Entry> get(String stepId) {
        return Optional.ofNullable(entries.get(stepId));
    }

    @Override
    public synchronized void put(String stepId, Entry entry) {
        entries.put(stepId, entry);
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            JSON.writeValue(tmp.toFile(), entries);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write run journal " + file, e);
        }
    }

    @Override
    public synchronized Map<String, Entry> all() {
        return Map.copyOf(entries);
    }

    @Override
    public boolean isDurable() {
        return true;
    }
}
