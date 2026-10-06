package io.github.llm4j.loom.init;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Copies a template into a folder, filling in {@code {{name}}}, and never overwrites a file: if any file is already there, nothing is written. */
public final class TemplateWriter {

    /** What happened: the files written (relative to the folder), or the files that were in the way. */
    public record Result(List<String> created, List<String> conflicts) {
        public boolean ok() {
            return conflicts.isEmpty();
        }
    }

    private TemplateWriter() {}

    /** The text of one file of a template, as it is in the jar, or null when there is no such file. */
    public static String read(String template, String file) {
        try (InputStream in = TemplateWriter.class.getResourceAsStream("/templates/" + template + "/" + file)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes {@code files} of {@code template} (a folder name under /templates) into {@code dir}. */
    public static Result write(String template, List<String> files, Path dir, Map<String, String> variables) throws IOException {
        Path root = dir.toAbsolutePath().normalize();
        List<String> conflicts = new ArrayList<>();
        for (String file : files) {
            Path target = root.resolve(file).normalize();
            if (!target.startsWith(root)) throw new IllegalArgumentException("a template file may not leave the folder: " + file);
            if (Files.exists(target)) conflicts.add(file);
            if (read(template, file) == null) throw new IllegalStateException("the template " + template + " has no file " + file + " (a broken jar?)");
        }
        if (!conflicts.isEmpty()) return new Result(List.of(), conflicts);
        List<String> created = new ArrayList<>();
        for (String file : files) {
            String text = read(template, file);
            for (var v : variables.entrySet()) text = text.replace("{{" + v.getKey() + "}}", v.getValue());
            Path target = root.resolve(file).normalize();
            Files.createDirectories(target.getParent());
            Files.writeString(target, text, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW);
            created.add(file);
        }
        return new Result(created, List.of());
    }
}
