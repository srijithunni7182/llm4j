package io.github.llm4j.loom.generate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * A generated project in a temporary folder. The gates run on it; only when they pass is it moved
 * to the target, so a target never holds a draft.
 */
public final class Workspace implements AutoCloseable {

    private final Path dir;
    private final List<String> files;
    private final boolean owned;
    private final Path script;

    Workspace(Path dir, List<String> files) {
        this(dir, files, true, null);
    }

    private Workspace(Path dir, List<String> files, boolean owned, Path script) {
        this.dir = dir;
        this.files = List.copyOf(files);
        this.owned = owned;
        this.script = script;
    }

    /**
     * An existing project folder (hand-written or generated earlier): read by the gates, never
     * removed.
     */
    public static Workspace existing(Path dir) {
        return new Workspace(dir.toAbsolutePath().normalize(), List.of(), false, null);
    }

    /** As {@link #existing(Path)}, for a project whose script is not called main.loom. */
    public static Workspace existing(Path dir, Path script) {
        return new Workspace(dir.toAbsolutePath().normalize(), List.of(), false, script);
    }

    public Path dir() {
        return dir;
    }

    /** Every file, relative to the project folder, in the layout the project has. */
    public List<String> files() {
        return files;
    }

    /** The script, wherever the layout put it. */
    public Path script() {
        if (script != null) return script;
        Path flat = dir.resolve("main.loom");
        return Files.isRegularFile(flat) ? flat : dir.resolve("src/main/resources/main.loom");
    }

    /** The tool declarations, if the project has any. */
    public Path loot() {
        Path flat = dir.resolve("tools.loot");
        Path maven = dir.resolve("src/main/resources/tools.loot");
        return Files.isRegularFile(flat) ? flat : Files.isRegularFile(maven) ? maven : null;
    }

    public List<Path> testFiles() throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(p -> p.getFileName().toString().endsWith(".test.yaml"))
                    .sorted()
                    .forEach(out::add);
        }
        return out;
    }

    /** What the move to a target found in the way. */
    public record Committed(List<String> written, List<String> conflicts) {
        public boolean ok() {
            return conflicts.isEmpty();
        }
    }

    /**
     * Moves the project into {@code target}. A target must be empty or absent unless {@code
     * intoExisting}; even then no existing file is replaced: the files in the way are returned and
     * nothing is written.
     */
    public Committed commit(Path target, boolean intoExisting) throws IOException {
        Path root = target.toAbsolutePath().normalize();
        if (Files.exists(root)) {
            boolean empty;
            try (Stream<Path> s = Files.isDirectory(root) ? Files.list(root) : Stream.of(root)) {
                empty = Files.isDirectory(root) && s.findAny().isEmpty();
            }
            if (!empty && !intoExisting)
                return new Committed(
                        List.of(),
                        List.of(root + " is not empty (give --into-existing to add to it)"));
        }
        List<String> conflicts = new ArrayList<>();
        for (String f : files) if (Files.exists(root.resolve(f))) conflicts.add(f);
        if (!conflicts.isEmpty()) return new Committed(List.of(), conflicts);
        for (String f : files) {
            Path to = root.resolve(f).normalize();
            if (!to.startsWith(root))
                throw new IOException("A file may not leave the project: " + f);
            Files.createDirectories(to.getParent());
            Files.copy(dir.resolve(f), to);
        }
        return new Committed(files, List.of());
    }

    /**
     * A temporary project folder whose script is where the project keeps it (not necessarily
     * main.loom).
     */
    static Workspace owned(Path dir, List<String> files, Path script) {
        return new Workspace(dir, files, true, script);
    }

    @Override
    public void close() throws IOException {
        if (!owned || !Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator)
                Files.deleteIfExists(p);
        }
    }
}
