package io.github.llm4j.loom.execution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * The text of files an editor has open and not yet saved, for the calling thread. A script (or an
 * import) read through {@link #read} gives the buffer's text when there is one and the file's text
 * otherwise, and every path read is noted, so a host can tell what a result depended on. With no
 * buffers in place nothing changes: {@link #read} is {@link Files#readString}.
 */
public final class Buffers {

    private record Scope(Map<Path, String> texts, Set<Path> reads) {}

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private Buffers() {}

    /**
     * Runs {@code work} with these texts standing in for the files at those (absolute, normalised)
     * paths, noting every path read in {@code reads}.
     */
    public static <T> T with(Map<Path, String> texts, Set<Path> reads, Callable<T> work)
            throws Exception {
        Scope before = CURRENT.get();
        CURRENT.set(new Scope(texts == null ? Map.of() : texts, reads));
        try {
            return work.call();
        } finally {
            if (before == null) CURRENT.remove();
            else CURRENT.set(before);
        }
    }

    /** The text of the file, or of its buffer. */
    public static String read(Path file) throws IOException {
        Scope scope = CURRENT.get();
        if (scope == null) return Files.readString(file);
        Path key = file.toAbsolutePath().normalize();
        if (scope.reads() != null) scope.reads().add(key);
        String buffer = scope.texts().get(key);
        return buffer != null ? buffer : Files.readString(file);
    }

    /** Whether there is a file, or a buffer, at the path. */
    public static boolean exists(Path file) {
        Scope scope = CURRENT.get();
        if (scope != null && scope.texts().containsKey(file.toAbsolutePath().normalize()))
            return true;
        return Files.exists(file);
    }

    /** The paths that have buffers now (empty when none). */
    public static Set<Path> open() {
        Scope scope = CURRENT.get();
        return scope == null ? Set.of() : Collections.unmodifiableSet(scope.texts().keySet());
    }
}
