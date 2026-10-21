package io.github.llm4j.loom.inspect.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One JSON line per entry, appended, in a file only its owner can read. Each entry gets the time it
 * was written.
 */
public final class FileAuditSink implements AuditSink {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path file;
    private final Clock clock;

    public FileAuditSink(Path file, Clock clock) {
        this.file = file;
        this.clock = clock;
    }

    @Override
    public synchronized void record(Map<String, Object> entry) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("at", clock.instant().toString());
        line.putAll(entry);
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            boolean fresh = !Files.exists(file);
            Files.write(
                    file,
                    (JSON.writeValueAsString(line) + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            if (fresh) {
                try {
                    Files.setPosixFilePermissions(
                            file, PosixFilePermissions.fromString("rw-------"));
                } catch (UnsupportedOperationException ignored) {
                    // not a POSIX file system
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
