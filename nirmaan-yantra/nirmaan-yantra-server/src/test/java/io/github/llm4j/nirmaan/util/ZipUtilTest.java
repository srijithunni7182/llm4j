package io.github.llm4j.nirmaan.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZipUtilTest {

    @TempDir
    Path tempDir;

    @Test
    void testZipDirectory() throws IOException {
        // Setup: Create a dummy directory with files
        Path sourceDir = tempDir.resolve("project-files");
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("file1.txt"), "Hello World");
        Files.writeString(sourceDir.resolve("file2.java"), "public class Test {}");

        // Subdirectory
        Path subDir = sourceDir.resolve("src");
        Files.createDirectories(subDir);
        Files.writeString(subDir.resolve("Main.java"), "System.out.println();");

        // Destination Zip
        Path zipPath = tempDir.resolve("project.zip");

        // Action
        ZipUtil.zipDirectory(sourceDir, zipPath);

        // Verify
        assertTrue(Files.exists(zipPath));
        try (ZipFile zipFile = new ZipFile(zipPath.toFile())) {
            // file1 + file2 + src/ + src/Main.java = 4 entries?
            // ZipUtil implementation walks files. Standard zip usually includes logic.
            // Let's just check files exist in zip.
            assertTrue(zipFile.getEntry("file1.txt") != null);
            assertTrue(zipFile.getEntry("file2.java") != null);
            assertTrue(zipFile.getEntry("src/Main.java") != null);
        }
    }
}
