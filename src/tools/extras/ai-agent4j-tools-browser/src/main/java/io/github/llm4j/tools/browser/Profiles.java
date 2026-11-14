package io.github.llm4j.tools.browser;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;

/**
 * The browser's profile folders (D16 rule 4): fresh for each run, deleted when it ends, and, when a
 * crashed run left one with its browser still running, that browser is stopped before a new one
 * starts.
 */
final class Profiles {

    static final String PID = "llm4j.pid";

    private Profiles() {}

    /** Stops what an earlier run left in {@code profile} and removes it; true when a browser was stopped. */
    static boolean clearLeftover(Path profile) {
        boolean stopped = false;
        Path pid = profile.resolve(PID);
        if (Files.isRegularFile(pid)) {
            try {
                long n = Long.parseLong(Files.readString(pid).strip());
                Optional<ProcessHandle> h = ProcessHandle.of(n);
                if (h.isPresent() && h.get().isAlive() && isBrowser(h.get())) {
                    h.get().descendants().forEach(ProcessHandle::destroyForcibly);
                    h.get().destroyForcibly();
                    stopped = true;
                }
            } catch (IOException | NumberFormatException unreadable) {
                // nothing to stop: the folder is removed below
            }
        }
        delete(profile);
        return stopped;
    }

    /** Only a process that is a browser started with this profile is stopped, never another one. */
    private static boolean isBrowser(ProcessHandle h) {
        return h.info().commandLine().map(c -> c.contains("--user-data-dir=") && c.contains("--remote-debugging-port")).orElse(false);
    }

    static void delete(Path folder) {
        if (!Files.exists(folder)) return;
        try {
            Files.walkFileTree(folder, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // a profile that cannot be removed now is removed by the next run's cleanup
        }
    }
}
