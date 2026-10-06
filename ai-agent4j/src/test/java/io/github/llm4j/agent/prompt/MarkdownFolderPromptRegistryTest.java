package io.github.llm4j.agent.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Prompt files, requirements R1, R5 and R6.1 of .kiro/specs/loom-prompt-files. */
class MarkdownFolderPromptRegistryTest {

    @TempDir Path dir;

    private Path write(String relative, String content) throws IOException {
        Path file = dir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    @Test
    void anIdFolderHoldsVersionsAndALoneFileIsVersionOne() throws IOException {
        write("researcher/v1.md", "first");
        write("researcher/v2.md", "second");
        write("writer.md", "alone");

        var registry = new MarkdownFolderPromptRegistry(dir);

        assertThat(registry.ids()).containsExactly("researcher", "writer");
        assertThat(registry.versions("researcher")).containsExactly("v1", "v2");
        assertThat(registry.get("writer").orElseThrow().getVersion()).isEqualTo("v1");
        assertThat(registry.get("writer", "v1").orElseThrow().getTemplate()).isEqualTo("alone");
        assertThat(registry.problems()).isEmpty();
    }

    @Test
    void theLatestVersionIsTheHighestNumberNotTheLastAlphabetically() throws IOException {
        write("a/v2.md", "two");
        write("a/v10.md", "ten");
        write("a/v9.md", "nine");

        var registry = new MarkdownFolderPromptRegistry(dir);

        assertThat(registry.get("a").orElseThrow().getVersion()).isEqualTo("v10");
        assertThat(registry.versions("a")).containsExactly("v2", "v9", "v10");
    }

    @Test
    void frontMatterGivesDescriptionAndVariablesAndIsNotPartOfThePrompt() throws IOException {
        write("r.md", "---\ndescription: Finds background\nvariables: [topic, region]\n---\n\n\nYou are careful about {topic}.\n\n");

        var entry = new MarkdownFolderPromptRegistry(dir).entry("r", "v1").orElseThrow();

        assertThat(entry.text()).isEqualTo("You are careful about {topic}.");
        assertThat(entry.description()).isEqualTo("Finds background");
        assertThat(entry.variables()).containsExactly("topic", "region");
    }

    @Test
    void aFileWithoutFrontMatterIsTheWholeTextAndAHorizontalRuleLaterIsKept() throws IOException {
        write("r.md", "Intro\n\n---\n\nrest");

        assertThat(new MarkdownFolderPromptRegistry(dir).get("r").orElseThrow().getTemplate()).isEqualTo("Intro\n\n---\n\nrest");
    }

    @Test
    void windowsLineEndingsInFrontMatterAreAccepted() throws IOException {
        write("r.md", "---\r\ndescription: d\r\n---\r\nbody");

        var entry = new MarkdownFolderPromptRegistry(dir).entry("r", "v1").orElseThrow();

        assertThat(entry.description()).isEqualTo("d");
        assertThat(entry.text()).isEqualTo("body");
    }

    @Test
    void thingsThatAreNotPromptsAreIgnoredAndReported() throws IOException {
        write("Bad Name/v1.md", "x");
        write("ok/notes.txt", "x");
        write("ok/v1.md", "fine");
        write("README.txt", "x");
        write("UPPER.md", "x");

        var registry = new MarkdownFolderPromptRegistry(dir);

        assertThat(registry.ids()).containsExactly("ok");
        assertThat(registry.problems()).extracting(p -> p.file().getFileName().toString())
                .containsExactlyInAnyOrder("Bad Name", "notes.txt", "README.txt", "UPPER.md");
    }

    @Test
    void aFileOverTheLimitIsRefusedByName() throws IOException {
        write("big.md", "x".repeat(MarkdownFolderPromptRegistry.MAX_BYTES + 1));

        var registry = new MarkdownFolderPromptRegistry(dir);

        assertThat(registry.get("big")).isEmpty();
        assertThat(registry.problems()).singleElement().satisfies(p -> {
            assertThat(p.file().getFileName().toString()).isEqualTo("big.md");
            assertThat(p.message()).contains("over the 65536 byte limit");
        });
    }

    @Test
    void aFileThatIsNotUtf8IsRefused() throws IOException {
        Files.write(dir.resolve("bad.md"), new byte[] {(byte) 0xC3, 0x28}); // an invalid two-byte sequence

        var registry = new MarkdownFolderPromptRegistry(dir);

        assertThat(registry.get("bad")).isEmpty();
        assertThat(registry.problems()).singleElement().extracting(MarkdownFolderPromptRegistry.Problem::message).asString().contains("not valid UTF-8");
    }

    @Test
    void unicodeTextIsKept() throws IOException {
        Files.writeString(dir.resolve("u.md"), "naïve — 日本語", StandardCharsets.UTF_8);

        assertThat(new MarkdownFolderPromptRegistry(dir).get("u").orElseThrow().getTemplate()).isEqualTo("naïve — 日本語");
    }

    @Test
    void brokenFrontMatterRefusesTheFile() throws IOException {
        write("r.md", "---\nvariables: [unclosed\n---\nbody");

        var registry = new MarkdownFolderPromptRegistry(dir);

        assertThat(registry.get("r")).isEmpty();
        assertThat(registry.problems()).singleElement().extracting(MarkdownFolderPromptRegistry.Problem::message).asString().contains("front matter");
    }

    @Test
    void idsAndVersionsFromOutsideNeverReachAFile() throws IOException {
        write("a/v1.md", "inside");
        Path outside = Files.writeString(dir.resolveSibling("secret.md"), "outside");
        var registry = new MarkdownFolderPromptRegistry(dir);

        for (String id : List.of("../secret", "..", "/etc/passwd", "a/../../secret", "a/v1", "", "A")) {
            assertThat(registry.get(id)).as(id).isEmpty();
            assertThat(registry.get(id, "v1")).as(id).isEmpty();
        }
        for (String version : List.of("../v1", "v1.md", "V1", "1", "v", "v-1", "v1/../v1")) {
            assertThat(registry.get("a", version)).as(version).isEmpty();
        }
        assertThat(registry.get(null)).isEmpty();
        assertThat(registry.get("a", null)).isEmpty();
        Files.deleteIfExists(outside);
    }

    @Test
    void aLinkThatLeavesTheFolderIsRefused() throws IOException {
        Path outside = Files.writeString(dir.resolveSibling("outside-secret.md"), "outside");
        Path root = Files.createDirectory(dir.resolve("prompts"));
        try {
            Files.createSymbolicLink(root.resolve("leak.md"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            return; // this file system cannot make links; nothing to test
        }

        var registry = new MarkdownFolderPromptRegistry(root);

        assertThat(registry.get("leak")).isEmpty();
        assertThat(registry.problems()).singleElement().extracting(MarkdownFolderPromptRegistry.Problem::message).asString().contains("leaves the prompt folder");
        Files.deleteIfExists(outside);
    }

    @Test
    void aMissingFolderIsAnEmptyRegistryThatSaysSo() {
        var registry = new MarkdownFolderPromptRegistry(dir.resolve("nope"));

        assertThat(registry.exists()).isFalse();
        assertThat(registry.ids()).isEmpty();
        assertThat(registry.get("x")).isEmpty();
    }

    @Test
    void reloadPicksUpChanges() throws IOException {
        write("a/v1.md", "one");
        var registry = new MarkdownFolderPromptRegistry(dir);
        write("a/v2.md", "two");
        Files.writeString(dir.resolve("a/v1.md"), "ONE");

        registry.reload();

        assertThat(registry.get("a").orElseThrow().getTemplate()).isEqualTo("two");
        assertThat(registry.get("a", "v1").orElseThrow().getTemplate()).isEqualTo("ONE");
    }

    @Test
    void aWatchingRegistryReloadsByItself() throws Exception {
        write("a/v1.md", "one");
        try (var registry = MarkdownFolderPromptRegistry.watching(dir)) {
            write("a/v2.md", "two");

            long deadline = System.currentTimeMillis() + 10_000;
            while (registry.versions("a").size() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(50);

            assertThat(registry.versions("a")).containsExactly("v1", "v2");
        }
    }
}
