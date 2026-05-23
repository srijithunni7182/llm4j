package io.github.llm4j.tantrik.console.service;

import io.github.llm4j.tantrik.console.model.FileDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link FileService}.
 * Requirements: 2.2, 2.3, 2.4
 */
class FileServiceTest {

    @TempDir
    Path tempDir;

    FileService fileService;

    @BeforeEach
    void setUp() {
        fileService = new FileService(tempDir.toString());
    }

    // -------------------------------------------------------------------------
    // listFiles
    // -------------------------------------------------------------------------

    @Test
    void listFiles_returnsEmptyList_whenRootDirectoryDoesNotExist() {
        FileService service = new FileService(tempDir.resolve("nonexistent").toString());
        assertThat(service.listFiles()).isEmpty();
    }

    @Test
    void listFiles_returnsEmptyList_whenNoLoomFilesPresent() throws IOException {
        Files.writeString(tempDir.resolve("readme.txt"), "hello");
        assertThat(fileService.listFiles()).isEmpty();
    }

    @Test
    void listFiles_returnsSingleLoomFile() throws IOException {
        Files.writeString(tempDir.resolve("main.loom"), "workflow main {}");

        List<FileDescriptor> files = fileService.listFiles();

        assertThat(files).hasSize(1);
        assertThat(files.get(0).name()).isEqualTo("main.loom");
        assertThat(files.get(0).path()).isEqualTo("main.loom");
        assertThat(files.get(0).lastModified()).isNotNull();
    }

    @Test
    void listFiles_scansRecursively() throws IOException {
        Path subDir = tempDir.resolve("examples");
        Files.createDirectories(subDir);
        Files.writeString(tempDir.resolve("root.loom"), "workflow root {}");
        Files.writeString(subDir.resolve("nested.loom"), "workflow nested {}");
        Files.writeString(subDir.resolve("ignore.txt"), "not a loom file");

        List<FileDescriptor> files = fileService.listFiles();

        assertThat(files).hasSize(2);
        assertThat(files).extracting(FileDescriptor::name)
                .containsExactlyInAnyOrder("root.loom", "nested.loom");
    }

    @Test
    void listFiles_returnsRelativePaths() throws IOException {
        Path subDir = tempDir.resolve("sub");
        Files.createDirectories(subDir);
        Files.writeString(subDir.resolve("deep.loom"), "workflow deep {}");

        List<FileDescriptor> files = fileService.listFiles();

        assertThat(files).hasSize(1);
        // Path separator is normalised to '/'
        assertThat(files.get(0).path()).isEqualTo("sub/deep.loom");
    }

    // -------------------------------------------------------------------------
    // readFile
    // -------------------------------------------------------------------------

    @Test
    void readFile_returnsContent() throws IOException {
        String content = "workflow hello { agent greet {} }";
        Files.writeString(tempDir.resolve("hello.loom"), content, StandardCharsets.UTF_8);

        assertThat(fileService.readFile("hello.loom")).isEqualTo(content);
    }

    @Test
    void readFile_throwsNoSuchElementException_whenFileDoesNotExist() {
        assertThatThrownBy(() -> fileService.readFile("missing.loom"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("missing.loom");
    }

    @Test
    void readFile_throwsIllegalArgumentException_onPathTraversal() {
        assertThatThrownBy(() -> fileService.readFile("../secret.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Path traversal detected");
    }

    @Test
    void readFile_throwsIllegalArgumentException_onAbsolutePathTraversal() {
        // Absolute path that escapes the root
        assertThatThrownBy(() -> fileService.readFile("/etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Path traversal detected");
    }

    @Test
    void readFile_throwsIllegalArgumentException_onBlankPath() {
        assertThatThrownBy(() -> fileService.readFile("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------
    // writeFile
    // -------------------------------------------------------------------------

    @Test
    void writeFile_createsFileWithContent() throws IOException {
        String content = "workflow new { agent bot {} }";
        fileService.writeFile("new.loom", content);

        assertThat(Files.readString(tempDir.resolve("new.loom"), StandardCharsets.UTF_8))
                .isEqualTo(content);
    }

    @Test
    void writeFile_overwritesExistingFile() throws IOException {
        Files.writeString(tempDir.resolve("existing.loom"), "old content");
        fileService.writeFile("existing.loom", "new content");

        assertThat(Files.readString(tempDir.resolve("existing.loom"), StandardCharsets.UTF_8))
                .isEqualTo("new content");
    }

    @Test
    void writeFile_createsParentDirectories() throws IOException {
        fileService.writeFile("sub/dir/script.loom", "workflow sub {}");

        assertThat(tempDir.resolve("sub/dir/script.loom")).exists();
        assertThat(Files.readString(tempDir.resolve("sub/dir/script.loom"), StandardCharsets.UTF_8))
                .isEqualTo("workflow sub {}");
    }

    @Test
    void writeFile_throwsIllegalArgumentException_onPathTraversal() {
        assertThatThrownBy(() -> fileService.writeFile("../../evil.loom", "bad"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Path traversal detected");
    }

    // -------------------------------------------------------------------------
    // resolveSafely — edge cases
    // -------------------------------------------------------------------------

    @Test
    void resolveSafely_allowsNestedPath() {
        Path resolved = fileService.resolveSafely("a/b/c.loom");
        assertThat(resolved.toString()).startsWith(tempDir.toAbsolutePath().normalize().toString());
    }

    @Test
    void resolveSafely_rejectsNullPath() {
        assertThatThrownBy(() -> fileService.resolveSafely(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolveSafely_rejectsEncodedTraversal() {
        // Even if the caller passes a path with multiple ".." segments
        assertThatThrownBy(() -> fileService.resolveSafely("a/../../outside.loom"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Path traversal detected");
    }
}
