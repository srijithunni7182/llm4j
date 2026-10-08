package io.github.llm4j.eval.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * Writes one run bundle: append-only JSONL files (crash-safe: every line is complete) plus an
 * atomically replaced {@code run.json}. Never throws to callers; a failed write marks the writer
 * failed and later writes are skipped.
 */
final class RunWriter {

    static final ObjectMapper MAPPER =
            new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private final Path dir;
    private final Path root;
    private volatile boolean failed;

    RunWriter(Path root, String runId) {
        this.root = root;
        this.dir = root.resolve("runs").resolve(runId);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            fail(e);
        }
    }

    boolean failed() {
        return failed;
    }

    Path dir() {
        return dir;
    }

    synchronized void append(String file, Object line) {
        if (failed) {
            return;
        }
        try {
            String json = MAPPER.writeValueAsString(line) + "\n";
            Files.writeString(
                    dir.resolve(file),
                    json,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            fail(e);
        }
    }

    synchronized void writeRun(Map<String, Object> run) {
        if (failed) {
            return;
        }
        try {
            Path tmp = dir.resolve("run.json.tmp");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), run);
            Files.move(
                    tmp,
                    dir.resolve("run.json"),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            fail(e);
        }
    }

    synchronized void appendIndex(Map<String, Object> entry) {
        try {
            Files.createDirectories(root);
            Files.writeString(
                    root.resolve("index.jsonl"),
                    MAPPER.writeValueAsString(entry) + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            fail(e);
        }
    }

    private void fail(IOException e) {
        if (!failed) {
            failed = true;
            System.err.println("eval4j: run export disabled, cannot write " + dir + ": " + e);
        }
    }
}
