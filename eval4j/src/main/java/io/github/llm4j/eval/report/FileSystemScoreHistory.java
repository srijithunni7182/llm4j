package io.github.llm4j.eval.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * JSON-lines score history in a single file, meant to live in the same CI cache directory as {@code
 * FileSystemJudgeCache}. Keeps the newest {@code retention} entries. Corrupt lines are skipped with
 * a warning rather than failing the tests; appends are atomic and serialized within the JVM.
 */
public final class FileSystemScoreHistory implements ScoreHistory {

    public static final int DEFAULT_RETENTION = 200;

    private static final Object LOCK = new Object();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;
    private final int retention;

    public FileSystemScoreHistory(Path file) {
        this(file, DEFAULT_RETENTION);
    }

    public FileSystemScoreHistory(Path file, int retention) {
        if (retention < 1) {
            throw new IllegalArgumentException("retention must be at least 1, got: " + retention);
        }
        this.file = file;
        this.retention = retention;
    }

    @Override
    public void append(HistoryEntry entry) {
        synchronized (LOCK) {
            List<HistoryEntry> entries = load();
            entries.add(entry);
            int from = Math.max(0, entries.size() - retention);
            StringBuilder out = new StringBuilder();
            try {
                for (HistoryEntry e : entries.subList(from, entries.size())) {
                    out.append(MAPPER.writeValueAsString(e)).append('\n');
                }
                AtomicFiles.write(file, out.toString().getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException("Could not write score history " + file, e);
            }
        }
    }

    @Override
    public List<HistoryEntry> load() {
        List<HistoryEntry> entries = new ArrayList<>();
        if (!Files.exists(file)) {
            return entries;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    entries.add(MAPPER.readValue(line, HistoryEntry.class));
                } catch (IOException e) {
                    System.err.println("eval4j: skipping corrupt history line in " + file);
                }
            }
        } catch (IOException e) {
            System.err.println("eval4j: could not read score history " + file + ": " + e);
        }
        return entries;
    }
}
