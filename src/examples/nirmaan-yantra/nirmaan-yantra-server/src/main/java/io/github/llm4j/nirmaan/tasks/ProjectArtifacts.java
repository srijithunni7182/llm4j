package io.github.llm4j.nirmaan.tasks;

import io.github.llm4j.agent.task.TaskNotPerformed;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * What a person may see of a project: its files (never build output), one file's text, and a zip of the lot. The only door the web
 * host uses to the project folder, so every path goes through {@link Sandbox}.
 */
public final class ProjectArtifacts {

    private static final int MAX_READ_BYTES = 1_000_000;

    private ProjectArtifacts() {}

    /** Relative paths of every file in the project, sorted, without build output. */
    public static List<String> list(String project) throws IOException {
        Path root = Sandbox.code(project);
        return ProjectFiles.list(root).stream().map(p -> p.toString().replace('\\', '/')).toList();
    }

    /** One file's text, or empty when it does not exist, is not a regular file, is too big or is not text. */
    public static Optional<String> read(String project, String relative) throws IOException {
        Path root = Sandbox.code(project);
        Path file;
        try {
            file = Sandbox.resolve(root, relative);
        } catch (TaskNotPerformed refused) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(file) || Files.isSymbolicLink(file) || !file.toRealPath().startsWith(root) || Files.size(file) > MAX_READ_BYTES || !ProjectFiles.readable(file)) {
            return Optional.empty();
        }
        return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** Writes a zip of the project (build output and tooling folders left out) to {@code target}; returns how many files it holds. */
    public static int zip(String project, Path target) throws IOException {
        Path root = Sandbox.code(project);
        int count = 0;
        try (OutputStream out = Files.newOutputStream(target); ZipOutputStream archive = new ZipOutputStream(out)) {
            for (Path file : ProjectFiles.list(root)) {
                archive.putNextEntry(new ZipEntry(file.toString().replace('\\', '/')));
                Files.copy(root.resolve(file), archive);
                archive.closeEntry();
                count++;
            }
        }
        return count;
    }

    /** Where {@link PackageProject} puts the finished zip. */
    public static Path archivePath(String project) {
        return Sandbox.code(project).getParent().resolve(project + "_FINAL.zip");
    }
}
