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

    /**
     * Where a template file goes. A file named {@code dot-gitignore} or {@code dot-env.example} is kept under that name in the jar (a real
     * {@code .gitignore} there would be dropped by the build and would act on the repository) and is written as {@code .gitignore} or {@code .env.example}.
     */
    public static String targetName(String file) {
        int slash = file.lastIndexOf('/');
        String name = file.substring(slash + 1);
        return name.startsWith("dot-") ? file.substring(0, slash + 1) + "." + name.substring(4) : file;
    }

    private static boolean isGitignore(String targetName) {
        return targetName.equals(".gitignore") || targetName.endsWith("/.gitignore");
    }

    /** Some files of one template folder. */
    public record Part(String template, List<String> files) {}

    /** Writes {@code files} of {@code template} (a folder name under /templates) into {@code dir}. */
    public static Result write(String template, List<String> files, Path dir, Map<String, String> variables) throws IOException {
        return write(List.of(new Part(template, files)), dir, variables);
    }

    /**
     * Writes several parts into {@code dir} as one: if any file of any part is already there, nothing is written, so a project is never half made.
     */
    public static Result write(List<Part> parts, Path dir, Map<String, String> variables) throws IOException {
        Path root = dir.toAbsolutePath().normalize();
        List<String> conflicts = new ArrayList<>();
        for (Part part : parts) {
            for (String file : part.files()) {
                String name = targetName(file);
                Path target = root.resolve(name).normalize();
                if (!target.startsWith(root)) throw new IllegalArgumentException("a template file may not leave the folder: " + file);
                // an existing .gitignore is added to, never a conflict
                if (Files.exists(target) && !isGitignore(name) && !conflicts.contains(name)) conflicts.add(name);
                if (read(part.template(), file) == null) throw new IllegalStateException("the template " + part.template() + " has no file " + file + " (a broken jar?)");
            }
        }
        if (!conflicts.isEmpty()) return new Result(List.of(), conflicts);
        List<String> created = new ArrayList<>();
        for (Part part : parts) {
            for (String file : part.files()) {
                String text = read(part.template(), file);
                for (var v : variables.entrySet()) text = text.replace("{{" + v.getKey() + "}}", v.getValue());
                String name = targetName(file);
                Path target = root.resolve(name).normalize();
                Files.createDirectories(target.getParent());
                if (isGitignore(name) && Files.exists(target)) {
                    List<String> have = Files.readAllLines(target, StandardCharsets.UTF_8).stream().map(String::strip).toList();
                    StringBuilder add = new StringBuilder();
                    for (String line : text.split("\\R")) if (!line.isBlank() && !have.contains(line.strip())) add.append(line).append('\n');
                    if (add.length() > 0) {
                        String existing = Files.readString(target, StandardCharsets.UTF_8);
                        Files.writeString(target, existing + (existing.endsWith("\n") || existing.isEmpty() ? "" : "\n") + add, StandardCharsets.UTF_8);
                        created.add(name + " (added to)");
                    }
                    continue;
                }
                Files.writeString(target, text, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW);
                created.add(name);
            }
        }
        return new Result(created, List.of());
    }
}
