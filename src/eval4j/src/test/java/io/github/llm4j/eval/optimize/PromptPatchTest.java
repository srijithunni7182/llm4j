package io.github.llm4j.eval.optimize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Tag;

class PromptPatchTest {

    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void reportsOnlyChangedParameters() {
        PromptPatch patch =
                new PromptPatch(map("a", "same", "b", "old"), map("a", "same", "b", "new"));

        assertThat(patch.changedParameters()).containsExactly("b");
        assertThat(patch.isEmpty()).isFalse();
        assertThat(patch.newText("b")).isEqualTo("new");
        assertThat(new PromptPatch(map("a", "x"), map("a", "x")).isEmpty()).isTrue();
        assertThat(new PromptPatch(map("a", "x"), map("a", "x")).unifiedDiff()).isEmpty();
        assertThatThrownBy(() -> patch.newText("zzz")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unifiedDiffNamesTheFileAndShowsRemovedAndAddedLines() {
        PromptPatch patch =
                new PromptPatch(
                        map("system-prompt", "line one\nline two\nline three\n"),
                        map("system-prompt", "line one\nline TWO\nline three\nline four\n"));

        String diff = patch.unifiedDiff();

        assertThat(diff).startsWith("--- a/system-prompt.txt\n+++ b/system-prompt.txt\n@@");
        assertThat(diff)
                .contains("-line two\n")
                .contains("+line TWO\n")
                .contains("+line four\n")
                .contains(" line one\n");
    }

    @Test
    void applyToWritesNewTextWithATrailingNewlineAndOnlyForChangedParameters(@TempDir Path dir)
            throws Exception {
        PromptPatch patch =
                new PromptPatch(
                        map("a", "keep", "system prompt/x", "old"),
                        map("a", "keep", "system prompt/x", "new text"));

        patch.applyTo(dir);

        assertThat(Files.readString(dir.resolve("system_prompt_x.txt"))).isEqualTo("new text\n");
        assertThat(dir.resolve("a.txt")).doesNotExist();
    }

    @Test
    void parameterNamesCannotEscapeTheDirectory(@TempDir Path dir) throws Exception {
        new PromptPatch(map("../../etc/passwd", "old"), map("../../etc/passwd", "evil"))
                .applyTo(dir);

        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .containsExactly(".._.._etc_passwd.txt");
        }
    }

    @Test
    void collidingFileNamesAreRejectedBeforeAnythingIsWritten(@TempDir Path dir) throws Exception {
        PromptPatch patch =
                new PromptPatch(map("a b", "1", "a_b", "1"), map("a b", "2", "a_b", "2"));
        assertThatThrownBy(() -> patch.applyTo(dir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("same file name");
        try (var files = Files.list(dir)) {
            assertThat(files.count()).isZero();
        }
    }

    /** The diff must be a real, applicable patch: check it with git when git is available. */
    @Tag("fragile") // runs git
    @Test
    void theDiffAppliesCleanlyWithGit(@TempDir Path dir) throws Exception {
        String before = "You are a helpful assistant.\nAnswer briefly.\nNever guess.\n";
        String after =
                "You are a helpful assistant.\nAnswer briefly and cite units.\nNever guess.\nAsk when unsure.\n";
        Files.writeString(dir.resolve("system-prompt.txt"), before);
        Files.writeString(
                dir.resolve("change.patch"),
                new PromptPatch(map("system-prompt", before), map("system-prompt", after))
                        .unifiedDiff());
        Process git;
        try {
            git =
                    new ProcessBuilder("git", "apply", "change.patch")
                            .directory(dir.toFile())
                            .redirectErrorStream(true)
                            .start();
        } catch (IOException noGit) {
            return; // git unavailable: the textual assertions above still cover the format
        }
        assertThat(git.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(git.exitValue()).as(new String(git.getInputStream().readAllBytes())).isZero();
        assertThat(Files.readString(dir.resolve("system-prompt.txt"))).isEqualTo(after);
    }
}
