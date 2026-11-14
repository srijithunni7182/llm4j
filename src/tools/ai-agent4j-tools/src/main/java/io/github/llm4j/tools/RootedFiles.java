package io.github.llm4j.tools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * Reads text files under one root for a tool that takes a path from its caller: a path that climbs
 * out, is absolute elsewhere or leads out through a symbolic link is refused, and a file larger
 * than the cap is refused, not cut.
 */
final class RootedFiles {

    private final Path root;
    private final long maxBytes;

    /** {@code root} null means files cannot be read: the tool takes text only. */
    RootedFiles(Path root, long maxBytes) {
        this.root = root;
        this.maxBytes = maxBytes;
    }

    boolean enabled() {
        return root != null;
    }

    /** The file's text; refused with a reason for anything outside the root, missing or too big. */
    String read(String path) {
        if (root == null)
            throw new ToolRefusal(
                    "this tool reads no files: declare root: (a directory) to let it read " + path);
        Path p;
        try {
            p = SafePaths.inside(root, path);
        } catch (IllegalArgumentException e) {
            throw new ToolRefusal(path + " is outside the tool's root");
        }
        try {
            if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(p))
                throw new ToolRefusal(path + " is not a file under the tool's root");
            if (Files.size(p) > maxBytes)
                throw new ToolRefusal(path + " is larger than " + maxBytes + " bytes (max_bytes)");
            try (InputStream in = Files.newInputStream(p)) {
                byte[] bytes = in.readNBytes((int) maxBytes + 1);
                if (bytes.length > maxBytes)
                    throw new ToolRefusal(
                            path + " is larger than " + maxBytes + " bytes (max_bytes)");
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (NoSuchFileException e) {
            throw new ToolRefusal(path + " is not a file under the tool's root");
        } catch (IOException e) {
            throw new ToolRefusal("could not read " + path + ": " + e.getClass().getSimpleName());
        }
    }

    /** Text given directly, held to the same cap. */
    String bounded(String key, String text) {
        if (text.getBytes(StandardCharsets.UTF_8).length > maxBytes)
            throw new ToolRefusal(key + " is larger than " + maxBytes + " bytes (max_bytes)");
        return text;
    }

    /** Resolves a declared root for a kind's checks, or throws {@link OptionException}. */
    static Path resolveRoot(String root, Path baseDir) {
        try {
            return SafePaths.inside(baseDir, root);
        } catch (IllegalArgumentException e) {
            throw new OptionException("root: " + e.getMessage());
        }
    }
}
