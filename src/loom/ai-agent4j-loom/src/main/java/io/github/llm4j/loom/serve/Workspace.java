package io.github.llm4j.loom.serve;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** The folders a client has open. A request names a file inside one of them or is refused. */
public final class Workspace {

    private final List<Path> roots;

    public Workspace(List<Path> roots) {
        this.roots = roots.stream().map(Workspace::real).toList();
    }

    /** The absolute, normalised path of a file the request names; refuses anything outside. */
    public Path resolve(String file) throws RpcError {
        if (file == null || file.isBlank())
            throw new RpcError(RpcError.INVALID_PARAMS, "invalid-params", "file is required");
        Path path = real(Path.of(file));
        for (Path root : roots)
            if (path.startsWith(root)) return Path.of(file).toAbsolutePath().normalize();
        throw new RpcError(
                RpcError.OUTSIDE_WORKSPACE,
                "outside-workspace",
                file + " is not inside the folders this server was opened for: " + roots);
    }

    public List<Path> roots() {
        return roots;
    }

    /** The path with links resolved when it exists (so a link cannot lead out), else normalised. */
    private static Path real(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        try {
            if (Files.exists(abs)) return abs.toRealPath();
            Path parent = abs.getParent();
            if (parent != null && Files.exists(parent))
                return parent.toRealPath().resolve(abs.getFileName());
        } catch (IOException e) {
            // fall through to the normalised path
        }
        return abs;
    }
}
