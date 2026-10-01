package io.github.llm4j.loom.tools.generic;

import io.github.llm4j.loom.tools.SafePaths;
import java.nio.file.Path;
import java.util.Set;

/**
 * Where an agent may touch files: inside one directory, never hidden, never what the run itself keeps (its
 * journal and trigger store), never through a symbolic link that leads out.
 */
public final class PathGuard {

    private final Path root;
    private final Set<Path> reserved;

    public PathGuard(Path root, Set<Path> reserved) {
        this.root = root;
        this.reserved = reserved;
    }

    /** @throws ToolRefusal if the path is not one the agent may use */
    public Path resolve(String given) {
        Path target;
        try {
            target = SafePaths.inside(root, given);
        } catch (IllegalArgumentException e) {
            throw new ToolRefusal("refused: " + e.getMessage());
        }
        for (Path segment : root.relativize(target)) {
            String name = segment.toString();
            if (!name.isEmpty() && name.startsWith(".")) throw new ToolRefusal("refused: hidden files are not available");
        }
        Path absolute = target.toAbsolutePath().normalize();
        for (Path r : reserved) {
            if (absolute.startsWith(r)) throw new ToolRefusal("refused: that location belongs to the run itself");
        }
        return target;
    }

    /** True when {@code path} (already resolved) is one of the run's own files. */
    public boolean isReserved(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        return reserved.stream().anyMatch(absolute::startsWith);
    }
}
