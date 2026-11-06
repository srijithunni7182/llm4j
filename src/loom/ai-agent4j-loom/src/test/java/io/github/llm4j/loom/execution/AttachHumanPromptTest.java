package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.ast.HumanPromptStmt;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.runtime.HumanInterface;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.model.MediaKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * docs/specs/31-multimodal-input §5.4: {@code human_prompt ... attach image "..."} shows a person a
 * file with the question. A channel that cannot show a picture still tells the person it is there
 * and where it is, and a resumed run notices if the file changed since the question was shown.
 */
class AttachHumanPromptTest {

    @TempDir Path dir;

    private final List<String> shown = Collections.synchronizedList(new ArrayList<>());

    private static byte[] png(int variant) {
        byte[] bytes = new byte[64];
        byte[] magic = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(magic, 0, bytes, 0, magic.length);
        bytes[40] = (byte) variant;
        return bytes;
    }

    @BeforeEach
    void assets() throws IOException {
        Files.createDirectories(dir.resolve("assets/2026"));
        Files.write(dir.resolve("assets/receipt.png"), png(1));
        Files.write(dir.resolve("assets/2026/other.png"), png(1));
    }

    private static String script(String step) {
        return "media_root: \"assets\"\nworkflow Main(receipt) {\n" + step + "\n}\n";
    }

    private HarnessExecutor executor(String source, RunJournal journal) {
        LoomScript script = new LoomParser(new Lexer(source).tokenize()).parseScript();
        HarnessExecutor executor =
                new HarnessExecutor(
                        script,
                        new ToolRegistry(),
                        model -> {
                            throw new UnsupportedOperationException("no model is called");
                        });
        executor.setBaseDir(dir);
        if (journal != null) executor.setJournal(journal);
        executor.setHumanInterface(
                message -> {
                    shown.add(message);
                    return "yes";
                });
        executor.initialize();
        return executor;
    }

    private static final String ASK =
            "human_prompt \"Is this receipt legible?\" attach image \"{receipt}.png\" -> answer";

    // ── parsing ────────────────────────────────────────────────────────────────────────────

    @Test
    void theAttachClauseComesBeforeTheArrow() {
        HumanPromptStmt stmt =
                (HumanPromptStmt)
                        new LoomParser(new Lexer(script(ASK)).tokenize())
                                .parseScript()
                                .getWorkflows()
                                .get(0)
                                .getStatements()
                                .get(0);

        assertThat(stmt.getVariableName()).isEqualTo("answer");
        assertThat(stmt.getAttachments()).hasSize(1);
        assertThat(stmt.getAttachments().get(0).kind()).isEqualTo(MediaKind.IMAGE);
        assertThat(stmt.getAttachments().get(0).path()).isEqualTo("{receipt}.png");
    }

    @Test
    void aPromptWithoutAttachmentsIsAsItWas() {
        HumanPromptStmt stmt =
                (HumanPromptStmt)
                        new LoomParser(new Lexer(script("human_prompt \"ok?\" -> answer")).tokenize())
                                .parseScript()
                                .getWorkflows()
                                .get(0)
                                .getStatements()
                                .get(0);

        assertThat(stmt.getAttachments()).isEmpty();
    }

    // ── what the person is shown ───────────────────────────────────────────────────────────

    @Test
    void thePersonIsToldWhatIsAttachedAndWhereItIs() {
        executor(script(ASK), null).executeWorkflow("Main", Map.of("receipt", "receipt"));

        assertThat(shown).hasSize(1);
        assertThat(shown.get(0))
                .startsWith("Is this receipt legible?")
                .contains("image")
                .contains("receipt.png")
                .contains("64 bytes")
                // where to open it, relative to the media folder, never an absolute path
                .doesNotContain(dir.toString());
    }

    @Test
    void aFileInASubfolderIsShownByItsPathInTheMediaFolder() {
        executor(
                        script("human_prompt \"ok?\" attach image \"{receipt}\" -> answer"),
                        null)
                .executeWorkflow("Main", Map.of("receipt", "2026/other.png"));

        assertThat(shown.get(0)).contains("2026/other.png");
    }

    @Test
    void aPromptWithNoAttachmentsIsShownExactlyAsWritten() {
        executor(script("human_prompt \"ok?\" -> answer"), null)
                .executeWorkflow("Main", Map.of("receipt", "receipt"));

        assertThat(shown).containsExactly("ok?");
    }

    // ── the same refusals as every step ────────────────────────────────────────────────────

    @Test
    void aPathThatEscapesTheFolderStopsTheStepBeforeAnyoneIsAsked() throws IOException {
        Files.write(dir.resolve("secret.png"), png(1));

        assertThatThrownBy(
                        () ->
                                executor(script(ASK), null)
                                        .executeWorkflow("Main", Map.of("receipt", "../secret")))
                .hasStackTraceContaining("attach image");
        assertThat(shown).isEmpty();
    }

    @Test
    void aMissingFileStopsTheStepBeforeAnyoneIsAsked() {
        assertThatThrownBy(
                        () ->
                                executor(script(ASK), null)
                                        .executeWorkflow("Main", Map.of("receipt", "missing")))
                .hasStackTraceContaining("missing.png");
        assertThat(shown).isEmpty();
    }

    // ── a resumed run ──────────────────────────────────────────────────────────────────────

    @Test
    void anAnswerIsNotReusedIfTheFileChangedSinceItWasShown() throws IOException {
        HarnessExecutor first = executor(script(ASK), null);
        first.executeWorkflow("Main", Map.of("receipt", "receipt"));
        RunJournal journal = first.getJournal();
        Files.write(dir.resolve("assets/receipt.png"), png(2));

        assertThatThrownBy(
                        () ->
                                executor(script(ASK), journal)
                                        .executeWorkflow("Main", Map.of("receipt", "receipt")))
                .hasStackTraceContaining("changed");
    }

    @Test
    void theJournalRecordsWhatWasShownAndNeverTheBytes() {
        HarnessExecutor first = executor(script(ASK), null);
        first.executeWorkflow("Main", Map.of("receipt", "receipt"));

        assertThat(first.getJournal().all().keySet()).anyMatch(k -> k.endsWith("#media"));
        assertThat(String.valueOf(first.getJournal().all()))
                .doesNotContain(java.util.Base64.getEncoder().encodeToString(png(1)));
    }
}
