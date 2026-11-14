package io.github.llm4j.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * Where a workspace tool may touch files (D11): inside its root, never through a symbolic link (a
 * program in a sandbox can make one inside the workspace, and a host-side tool that followed it
 * would reach the host), never into what the run keeps or the tool's own snapshot store.
 */
final class WorkspaceJail {

    private final Path root;
    private final Set<Path> reserved;

    WorkspaceJail(Path root, Set<Path> reserved) {
        this.root = root.toAbsolutePath().normalize();
        this.reserved = Set.copyOf(reserved);
    }

    Path root() {
        return root;
    }

    /** The path a caller named, checked; throws {@link ToolRefusal} saying why it is refused. */
    Path resolve(String given) {
        for (int i = 0; i < given.length(); i++) {
            char c = given.charAt(i);
            if (c < 0x20 || c == 0x7f || c == 0x85 || c == 0x2028 || c == 0x2029)
                throw new ToolRefusal(
                        "refused: the path contains a control or line-break character");
        }
        Path target;
        try {
            target = SafePaths.inside(root, given);
        } catch (IllegalArgumentException e) {
            throw new ToolRefusal("refused: " + given + " is outside the workspace");
        }
        Path current = root;
        for (Path segment : root.relativize(target)) {
            if (segment.toString().isEmpty()) continue;
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current))
                throw new ToolRefusal(
                        "refused: " + given + " goes through a link, and links are not followed");
        }
        if (isReserved(target))
            throw new ToolRefusal("refused: that location belongs to the run itself");
        return target;
    }

    boolean isReserved(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        return reserved.stream().anyMatch(absolute::startsWith);
    }

    /** {@code path} relative to the root, with {@code /} between names. */
    String relative(Path path) {
        return root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }
}
