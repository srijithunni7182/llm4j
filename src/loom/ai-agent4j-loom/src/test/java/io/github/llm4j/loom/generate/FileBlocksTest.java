package io.github.llm4j.loom.generate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** The files in a model's reply. */
class FileBlocksTest {

    @Test
    void filesAreReadFromTheirBlocksWhateverSurroundsThem() {
        String reply =
                "Sure!\n=== file: main.loom ===\nworkflow Main() {}\n=== end ===\nand\n=== file: prompts/a.md ===\nHello\n=== end ===\nBye";
        assertThat(FileBlocks.parse(reply))
                .containsExactly(
                        Map.entry("main.loom", "workflow Main() {}\n"),
                        Map.entry("prompts/a.md", "Hello\n"));
    }

    @Test
    void aCodeFenceInsideABlockIsDropped() {
        String reply = "=== file: main.loom ===\n```loom\nworkflow Main() {}\n```\n=== end ===";
        assertThat(FileBlocks.parse(reply).get("main.loom")).isEqualTo("workflow Main() {}");
    }

    @Test
    void aPathWrittenTwiceKeepsTheLastAndNoBlocksGivesNothing() {
        assertThat(
                        FileBlocks.parse(
                                        "=== file: a.md ===\n1\n=== end ===\n=== file: a.md ===\n2\n=== end ===")
                                .get("a.md"))
                .isEqualTo("2\n");
        assertThat(FileBlocks.parse("no files here")).isEmpty();
        assertThat(FileBlocks.parse(null)).isEmpty();
        assertThat(FileBlocks.parse("=== file: a.md ===\nnever ended")).isEmpty();
    }

    @Test
    void renderThenParseGivesTheSameFiles() {
        Map<String, String> files = Map.of("main.loom", "x\n");
        assertThat(FileBlocks.parse(FileBlocks.render(files))).isEqualTo(files);
    }

    @Test
    void aSloppyEndMarkerStillEndsTheFile() {
        assertThat(
                        FileBlocks.parse(
                                "=== file: a.md ===\nx\n=== end===\n=== file: b.md ===\ny\n=== end"))
                .containsOnlyKeys("a.md", "b.md");
    }
}
