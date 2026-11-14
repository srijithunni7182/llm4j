package io.github.llm4j.tools.docs.example;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.tool.ToolProfile;
import io.github.llm4j.tools.support.RecordingEffects;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The tools in the "Writing a tool" guide. The guide shows these classes as they are, and the last
 * test fails when the page and the code differ, so the steps on the page always work.
 */
class WritingAToolExamplesTest {

    @TempDir Path dir;
    final RecordingEffects effects = new RecordingEffects();

    // ── Step 3: check the options ──────────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            nullValues = "null",
            value = {
                "max_chars=500|null",
                "max_chars=0|max_chars: must be",
                "max_chars=100001|max_chars: must be",
                "max_chars=lots|max_chars: must be"
            })
    void checkNamesTheOptionThatIsWrong(String option, String problem) {
        String[] kv = option.split("=");

        String result = new WordCountKind().check(Map.of(kv[0], kv[1]), dir);

        if (problem == null) assertThat(result).isNull();
        else assertThat(result).contains(problem);
    }

    // ── Step 4: call the tool ──────────────────────────────────────────────────────────────

    Tool counter(String maxChars) throws Exception {
        return new WordCountKind().create("Count", Map.of("max_chars", maxChars), dir, effects);
    }

    @Test
    void countsWordsLinesAndCharacters() throws Exception {
        String result = counter("100").execute(Map.of("text", "one two\nthree"));

        assertThat(result).isEqualTo("{\"words\":3,\"lines\":2,\"characters\":13}");
    }

    @ParameterizedTest
    @CsvSource({"'', text is required", "'   ', text is required"})
    void blankTextIsRefused(String text, String reason) throws Exception {
        assertThat(counter("100").execute(Map.of("text", text)))
                .startsWith("Error:")
                .contains(reason);
    }

    @Test
    void textOverTheLimitIsRefusedAndSaysWhich() throws Exception {
        assertThat(counter("10").execute(Map.of("text", "x".repeat(11))))
                .startsWith("Error:")
                .contains("the most allowed is 10");
    }

    @Test
    void aModelCannotPassAListOrAnObjectAsText() throws Exception {
        Tool tool = counter("100");

        assertThat(tool.execute(Map.of("text", List.of("a")))).startsWith("Error:");
        assertThat(tool.execute(Map.of("text", Map.of("a", "b")))).startsWith("Error:");
    }

    @Test
    void theToolNeverThrowsAtTheAgent() throws Exception {
        assertThat(counter("100").execute(null)).startsWith("Error:");
    }

    // ── Step 5: say what it reaches ────────────────────────────────────────────────────────

    @Test
    void aCountingToolReachesNothing() {
        ToolProfile profile = new WordCountKind().profile(Map.of());

        assertThat(profile.untrusted()).isFalse();
        assertThat(profile.privateData()).isFalse();
        assertThat(profile.outward()).isFalse();
        assertThat(profile.effect().name()).isEqualTo("NONE");
    }

    @Test
    void aKindThatSaysNothingIsAssumedToReachEverything() {
        assertThat(new BareKind().profile(Map.of()).known()).isFalse();
        assertThat(new WordCountKind().profile(Map.of()).known()).isTrue();
    }

    /** A kind that does not override {@code profile}. */
    static final class BareKind extends io.github.llm4j.tools.GenericKind {
        @Override
        public String name() {
            return "bare";
        }

        @Override
        protected void validate(io.github.llm4j.tools.Options options, Path baseDir) {}

        @Override
        protected Tool build(
                String name,
                io.github.llm4j.tools.Options options,
                Path baseDir,
                io.github.llm4j.agent.tool.EffectContext context) {
            throw new UnsupportedOperationException();
        }
    }

    // ── A tool that changes something ──────────────────────────────────────────────────────

    Tool receipts() throws Exception {
        return new ReceiptKind().create("Receipts", Map.of("dir", "receipts"), dir, effects);
    }

    @Test
    void theSameCallOnTheSameJournalIsNotDoneTwice() throws Exception {
        Tool first = receipts();
        Tool afterARestart = receipts();

        String firstResult = first.execute(Map.of("line", "order 17 shipped"));
        String repeated = afterARestart.execute(Map.of("line", "order 17 shipped"));

        assertThat(firstResult).isEqualTo("Recorded.");
        assertThat(repeated).contains("already done");
        assertThat(lines()).containsExactly("order 17 shipped");
    }

    @Test
    void aDifferentCallIsDone() throws Exception {
        receipts().execute(Map.of("line", "a"));
        receipts().execute(Map.of("line", "b"));

        assertThat(lines()).containsExactly("a", "b");
    }

    @Test
    void aLineThatIsNotOneLineIsRefusedAndNothingIsWritten() throws Exception {
        assertThat(receipts().execute(Map.of("line", "a\nforged entry"))).startsWith("Error:");

        assertThat(dir.resolve("receipts/receipts.log")).doesNotExist();
    }

    @Test
    void theDirectoryCannotClimbOut() {
        assertThat(new ReceiptKind().check(Map.of("dir", "../elsewhere"), dir)).startsWith("dir:");
    }

    @Test
    void aChangingToolSaysSoAndIsJournaled() {
        ToolProfile profile = new ReceiptKind().profile(Map.of("dir", "receipts"));

        assertThat(profile.effect().name()).isEqualTo("CHANGES");
        assertThat(profile.journaled()).isTrue();
    }

    private List<String> lines() throws IOException {
        return Files.readAllLines(dir.resolve("receipts/receipts.log"));
    }

    // ── The page shows the code as it is ───────────────────────────────────────────────────

    @Test
    void theGuideShowsTheExampleClassesAsTheyAre() throws IOException {
        String page = Files.readString(Path.of("docs/writing-a-tool.md"));

        for (String file :
                List.of("WordCountKind", "WordCountTool", "ReceiptKind", "ReceiptTool")) {
            Path source =
                    Path.of(
                            "src/test/java/io/github/llm4j/tools/docs/example/" + file + ".java");
            String code = Files.readString(source);
            String shown = code.substring(code.indexOf("\n", code.indexOf("package ")) + 1).strip();
            assertThat(page).as(file + " as shown in the guide").contains(shown);
        }
    }
}
