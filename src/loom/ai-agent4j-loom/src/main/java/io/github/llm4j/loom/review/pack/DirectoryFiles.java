package io.github.llm4j.loom.review.pack;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The files of a pack in a folder. It reads only inside that folder: a path that climbs out of it,
 * is absolute or leads through a symbolic link that leaves it is refused, so a pack cannot read
 * anything that is not its own.
 */
final class DirectoryFiles implements PackFiles {

    private final Path root;

    DirectoryFiles(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public Optional<String> read(String relativePath) {
        Path p = root.resolve(relativePath).normalize();
        if (!p.startsWith(root) || !Files.isRegularFile(p)) return Optional.empty();
        try {
            if (!p.toRealPath().startsWith(root.toRealPath())) return Optional.empty();
            return Optional.of(Files.readString(p, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<String> ruleFiles() {
        Path rules = root.resolve("rules");
        if (!Files.isDirectory(rules)) return List.of();
        try (Stream<Path> s = Files.walk(rules)) {
            return s.filter(Files::isRegularFile)
                    .filter(
                            f ->
                                    f.toString().endsWith(".yaml")
                                            || f.toString().endsWith(".yml")
                                            || f.toString().endsWith(".json"))
                    .map(f -> root.relativize(f).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String where() {
        return root.toString();
    }
}
