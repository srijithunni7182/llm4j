package io.github.llm4j.tantrik.console.service;

import io.github.llm4j.tantrik.console.model.FileDescriptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

/**
 * Service for managing Loom script files on disk.
 *
 * <p>All file operations are scoped to the configured root directory
 * ({@code tantrik.console.loom-scripts.dir}). Any path that resolves outside
 * the root is rejected with an {@link IllegalArgumentException}, which the
 * {@code GlobalExceptionHandler} maps to HTTP 400.
 *
 * <p>Requirements: 2.2, 2.3, 2.4, 8.6
 */
@Service
public class FileService {

    private final Path rootDir;

    public FileService(
            @Value("${tantrik.console.loom-scripts.dir:./loom-scripts}") String loomScriptsDir) {
        this.rootDir = Paths.get(loomScriptsDir).toAbsolutePath().normalize();
    }

    /**
     * Scans the root directory recursively and returns a {@link FileDescriptor}
     * for every {@code .loom} file found.
     *
     * <p>If the root directory does not exist yet, an empty list is returned.
     *
     * @return list of file descriptors (never {@code null})
     */
    public List<FileDescriptor> listFiles() {
        if (!Files.exists(rootDir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(rootDir)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".loom"))
                    .map(this::toDescriptor)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan loom-scripts directory: " + rootDir, e);
        }
    }

    /**
     * Reads the content of the file at the given relative path.
     *
     * @param relativePath relative path from the root directory (e.g. {@code "examples/main.loom"})
     * @return file content as a UTF-8 string
     * @throws IllegalArgumentException if the path resolves outside the root directory
     * @throws java.util.NoSuchElementException if the file does not exist
     */
    public String readFile(String relativePath) {
        Path resolved = resolveSafely(relativePath);
        if (!Files.exists(resolved)) {
            throw new java.util.NoSuchElementException("File not found: " + relativePath);
        }
        try {
            return Files.readString(resolved, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read file: " + relativePath, e);
        }
    }

    /**
     * Writes {@code content} to the file at the given relative path, creating
     * any missing parent directories as needed.
     *
     * @param relativePath relative path from the root directory
     * @param content      UTF-8 content to write
     * @throws IllegalArgumentException if the path resolves outside the root directory
     */
    public void writeFile(String relativePath, String content) {
        Path resolved = resolveSafely(relativePath);
        try {
            Files.createDirectories(resolved.getParent());
            Files.writeString(resolved, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write file: " + relativePath, e);
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Resolves {@code relativePath} against the root directory and verifies that
     * the result is still inside the root (path-traversal guard).
     *
     * @throws IllegalArgumentException if the resolved path escapes the root
     */
    Path resolveSafely(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("File path must not be blank");
        }
        Path resolved = rootDir.resolve(relativePath).normalize();
        if (!resolved.startsWith(rootDir)) {
            throw new IllegalArgumentException(
                    "Path traversal detected: '" + relativePath + "' resolves outside the root directory");
        }
        return resolved;
    }

    private FileDescriptor toDescriptor(Path absolutePath) {
        Path relative = rootDir.relativize(absolutePath);
        String relativeStr = relative.toString().replace('\\', '/'); // normalise on Windows
        String name = absolutePath.getFileName().toString();
        Instant lastModified;
        try {
            lastModified = Files.getLastModifiedTime(absolutePath).toInstant();
        } catch (IOException e) {
            lastModified = Instant.EPOCH;
        }
        return new FileDescriptor(relativeStr, name, lastModified);
    }
}
