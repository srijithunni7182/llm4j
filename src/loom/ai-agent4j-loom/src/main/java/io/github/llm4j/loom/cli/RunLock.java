package io.github.llm4j.loom.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * Says that a process is working on a run directory, so an operator command doesn't change the journal under it. The lock is a file
 * holding the process id; a lock whose process has gone is stale and ignored.
 */
final class RunLock implements AutoCloseable {

    static final String FILE = "run.lock";

    private final Path file;

    private RunLock(Path file) {
        this.file = file;
    }

    /** A lock that holds nothing, for an operator who forced a change. */
    static RunLock none() {
        return new RunLock(null);
    }

    /** Takes the lock, or returns empty when another live process holds it. */
    static Optional<RunLock> tryAcquire(Path runDir) {
        Path file = runDir.resolve(FILE);
        try {
            Files.createDirectories(runDir);
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    Files.writeString(file, String.valueOf(ProcessHandle.current().pid()), StandardOpenOption.CREATE_NEW);
                    return Optional.of(new RunLock(file));
                } catch (java.nio.file.FileAlreadyExistsException held) {
                    if (holder(runDir).isPresent()) return Optional.empty();
                    Files.deleteIfExists(file); // stale: its process is gone
                }
            }
            return Optional.empty();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Cannot lock " + runDir, e);
        }
    }

    /** The process id working on the run directory, if one is alive. */
    static Optional<Long> holder(Path runDir) {
        Path file = runDir.resolve(FILE);
        try {
            if (!Files.exists(file)) return Optional.empty();
            long pid = Long.parseLong(Files.readString(file).strip());
            return ProcessHandle.of(pid).filter(ProcessHandle::isAlive).map(h -> pid);
        } catch (IOException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    @Override
    public void close() {
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // a leftover lock names a dead process and is ignored next time
        }
    }
}
