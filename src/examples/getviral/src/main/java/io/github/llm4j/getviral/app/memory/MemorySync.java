package io.github.llm4j.getviral.app.memory;

import io.github.llm4j.getviral.config.GetViralConfig;
import io.github.llm4j.getviral.engine.CreatorMemory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Engram keeps a creator's memory in a JSON working copy; the database is the source of truth.
 * Before a run (or a memory read) the copy is pulled from the database, and pushed back afterwards,
 * so memory follows the creator across Cloud Run instances.
 */
@Component
public class MemorySync {

    private final JdbcTemplate jdbc;
    private final GetViralConfig config;

    public MemorySync(JdbcTemplate jdbc, GetViralConfig config) {
        this.jdbc = jdbc;
        this.config = config;
    }

    public Path file(String userId) {
        return CreatorMemory.fileFor(config.dataDir(), userId);
    }

    public void pull(String userId) {
        List<String> rows = jdbc.query("select memory_json from creator_memory where user_id = ?",
                (rs, i) -> rs.getString(1), userId);
        Path file = file(userId);
        try {
            Files.createDirectories(file.getParent());
            if (rows.isEmpty()) {
                Files.deleteIfExists(file);
            } else {
                Files.writeString(file, rows.get(0));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot prepare memory for " + userId, e);
        }
    }

    public void push(String userId) {
        Path file = file(userId);
        if (!Files.exists(file)) return;
        try {
            String json = Files.readString(file);
            Timestamp now = Timestamp.from(Instant.now());
            if (jdbc.update("update creator_memory set memory_json = ?, updated_at = ? where user_id = ?", json, now, userId) == 0) {
                jdbc.update("insert into creator_memory (user_id, memory_json, updated_at) values (?, ?, ?)", userId, json, now);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot save memory for " + userId, e);
        }
    }
}
