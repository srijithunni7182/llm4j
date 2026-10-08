package io.github.llm4j.eval.report;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Write-to-temp-then-move so a failure mid-write never leaves a truncated report or baseline. */
public final class AtomicFiles {

    private AtomicFiles() {}

    public static void write(Path target, byte[] content) throws IOException {
        Path absolute = target.toAbsolutePath();
        Path dir = absolute.getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path temp = Files.createTempFile(dir, absolute.getFileName().toString(), ".tmp");
        try {
            Files.write(temp, content);
            try {
                Files.move(
                        temp,
                        absolute,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
