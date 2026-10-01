package io.github.llm4j.loom.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Keeps file access inside the script's directory: agents choose paths (what to transcribe, where
 * speech is written), so a path that climbs out ({@code ..}), is absolute elsewhere, or escapes through
 * a symbolic link is refused.
 */
public final class SafePaths {

    private SafePaths() {}

    /**
     * @return the path, resolved against {@code base}
     * @throws IllegalArgumentException if it lies outside {@code base}
     */
    public static Path inside(Path base, String path) {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("no path given");
        Path root = base.toAbsolutePath().normalize();
        Path p = root.resolve(path).normalize();
        if (!p.startsWith(root)) throw new IllegalArgumentException(path + " is outside the script's directory");
        try {
            // Neither the root nor the target has to exist yet (a write creates them): compare where each really
            // is, using the nearest part that does exist plus the part that doesn't.
            if (!realLocation(p).startsWith(realLocation(root))) {
                throw new IllegalArgumentException(path + " leads outside the script's directory");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("can't check " + path + ": " + e.getMessage(), e);
        }
        return p;
    }

    /** The real (symlink-free) location of a path, even if its last parts don't exist yet. */
    private static Path realLocation(Path path) throws IOException {
        Path existing = path;
        // A symbolic link counts as existing even when its target doesn't (it would be followed on a write).
        while (existing != null && !Files.exists(existing, java.nio.file.LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
        if (existing == null) return path;
        return existing.toRealPath().resolve(existing.relativize(path)).normalize();
    }
}
