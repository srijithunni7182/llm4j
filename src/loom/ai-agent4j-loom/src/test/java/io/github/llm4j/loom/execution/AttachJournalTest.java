package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.MediaCapabilities;
import io.github.llm4j.model.MediaKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * docs/specs/31-multimodal-input R9 and R20: the journal records what was attached (kind, type,
 * size, SHA-256, name; never the bytes), and a resumed run reuses an answer only if the files are
 * still the ones it was given. A changed file is never answered about under the old record.
 */
class AttachJournalTest {

    @TempDir Path dir;

    private final List<LLMRequest> sent = Collections.synchronizedList(new ArrayList<>());

    private static byte[] png(int variant) {
        byte[] bytes = new byte[64];
        byte[] magic = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(magic, 0, bytes, 0, magic.length);
        bytes[40] = (byte) variant; // same type and size, different content
        return bytes;
    }

    @BeforeEach
    void assets() throws IOException {
        Files.createDirectories(dir.resolve("assets"));
        Files.write(dir.resolve("assets/receipt.png"), png(1));
    }

    private LLMClientFactory models() {
        return model ->
                new LLMClient() {
                    @Override
                    public LLMResponse chat(LLMRequest request) {
                        sent.add(request);
                        String reply =
                                request.getMessages().get(0).getContent().contains("JSON object")
                                        ? "{\"total\":42.17}"
                                        : "```json\n{\"thought\":\"done\",\"final_answer\":\"42\"}\n```";
                        return LLMResponse.builder().content(reply).model(model).build();
                    }

                    @Override
                    public Stream<LLMResponse> chatStream(LLMRequest request) {
                        return Stream.of(chat(request));
                    }

                    @Override
                    public MediaCapabilities mediaCapabilities(String m) {
                        return MediaCapabilities.of(MediaKind.IMAGE);
                    }
                };
    }

    private static String script(String step) {
        return "media_root: \"assets\"\n"
                + "agent Reader { model: \"test\" system: \"x\" }\n"
                + "extractor Form { model: \"test\" fields { total: number } }\n"
                + "workflow Main(receipt) {\n"
                + step
                + "\n}\n";
    }

    private HarnessExecutor executor(String source, RunJournal journal) {
        LoomScript script = new LoomParser(new Lexer(source).tokenize()).parseScript();
        HarnessExecutor executor = new HarnessExecutor(script, new ToolRegistry(), models());
        executor.setBaseDir(dir);
        if (journal != null) executor.setJournal(journal);
        executor.initialize();
        return executor;
    }

    private RunJournal firstRun(String step) {
        HarnessExecutor first = executor(script(step), null);
        first.executeWorkflow("Main", Map.of("receipt", "receipt"));
        return first.getJournal();
    }

    private void resume(String step, RunJournal journal) {
        executor(script(step), journal).executeWorkflow("Main", Map.of("receipt", "receipt"));
    }

    private static final String DELEGATE =
            "delegate \"total?\" to Reader attach image \"{receipt}.png\" -> out";
    private static final String EXTRACT = "extract Form from image \"{receipt}.png\" -> fields";
    private static final String BROADCAST =
            "broadcast \"look\" to [Reader] attach image \"{receipt}.png\" -> out";
    private static final String HANDOFF = "handoff \"look\" to Reader attach image \"{receipt}.png\"";

    // ── what is written down ───────────────────────────────────────────────────────────────

    @Test
    void theJournalHoldsDescriptorsAndNeverTheBytes() {
        RunJournal journal = firstRun(DELEGATE);

        List<RunJournal.Entry> media =
                journal.all().entrySet().stream()
                        .filter(e -> e.getKey().endsWith("#media"))
                        .map(Map.Entry::getValue)
                        .toList();
        assertThat(media).hasSize(1);
        String written = String.valueOf(media.get(0).value());
        assertThat(written).contains("IMAGE").contains("image/png").contains("receipt.png").contains("sha256");
        assertThat(written).doesNotContain(Base64.getEncoder().encodeToString(png(1)));
    }

    @Test
    void aStepWithNoAttachmentsLeavesNoMediaRecord() {
        RunJournal journal = firstRun("delegate \"hello\" to Reader -> out");

        assertThat(journal.all().keySet()).noneMatch(k -> k.endsWith("#media"));
    }

    // ── resuming with the same file ────────────────────────────────────────────────────────

    @Test
    void resumingWithTheSameFileReusesTheAnswerAndCallsNoModel() {
        RunJournal journal = firstRun(DELEGATE);
        int calls = sent.size();

        resume(DELEGATE, journal);

        assertThat(sent).hasSize(calls);
    }

    @Test
    void theSameForExtractBroadcastAndHandoff() {
        for (String step : List.of(EXTRACT, BROADCAST, HANDOFF)) {
            sent.clear();
            RunJournal journal = firstRun(step);
            int calls = sent.size();
            assertThat(calls).as(step).isPositive();

            resume(step, journal);

            assertThat(sent).as(step).hasSize(calls);
        }
    }

    // ── resuming when the files are not the ones the answer was given ──────────────────────

    @Test
    void aChangedFileStopsTheResumeAndTheOldAnswerIsNotReused() throws IOException {
        RunJournal journal = firstRun(DELEGATE);
        int calls = sent.size();
        Files.write(dir.resolve("assets/receipt.png"), png(2));

        assertThatThrownBy(() -> resume(DELEGATE, journal))
                .hasStackTraceContaining("receipt.png")
                .hasStackTraceContaining("changed");
        assertThat(sent).hasSize(calls);
    }

    @Test
    void aDeletedFileStopsTheResumeToo() throws IOException {
        RunJournal journal = firstRun(DELEGATE);
        Files.delete(dir.resolve("assets/receipt.png"));

        assertThatThrownBy(() -> resume(DELEGATE, journal)).hasStackTraceContaining("receipt.png");
    }

    @Test
    void theSameHoldsForExtractBroadcastAndHandoff() throws IOException {
        for (String step : List.of(EXTRACT, BROADCAST, HANDOFF)) {
            Files.write(dir.resolve("assets/receipt.png"), png(1));
            RunJournal journal = firstRun(step);
            Files.write(dir.resolve("assets/receipt.png"), png(3));

            assertThatThrownBy(() -> resume(step, journal))
                    .as(step)
                    .hasStackTraceContaining("changed");
        }
    }

    @Test
    void aStepThatNowAttachesSomethingTheRecordedAnswerWasNeverGiven() {
        RunJournal journal = firstRun("delegate \"total?\" to Reader -> out");

        assertThatThrownBy(() -> resume(DELEGATE, journal))
                .hasStackTraceContaining("not given")
                .hasStackTraceContaining("attach");
    }

    @Test
    void aStepThatNoLongerAttachesWhatTheRecordedAnswerWasGiven() {
        RunJournal journal = firstRun(DELEGATE);

        assertThatThrownBy(() -> resume("delegate \"total?\" to Reader -> out", journal))
                .hasStackTraceContaining("no longer");
    }

    @Test
    void aStepThatNowAttachesADifferentNumberOfFilesThanTheRecordedAnswerWasGiven() {
        RunJournal journal = firstRun(DELEGATE);

        assertThatThrownBy(
                        () ->
                                resume(
                                        "delegate \"total?\" to Reader attach image \"{receipt}.png\", image \"{receipt}.png\" -> out",
                                        journal))
                .hasStackTraceContaining("1 attachment(s)")
                .hasStackTraceContaining("attaches 2");
    }

    @Test
    void theStepsOnFailureBlockHandlesAChangedFile() throws IOException {
        RunJournal journal = firstRun(DELEGATE);
        int calls = sent.size();
        Files.write(dir.resolve("assets/receipt.png"), png(2));

        resume(DELEGATE.replace("-> out", "-> out on_failure { note \"handled\" }"), journal);

        assertThat(sent).hasSize(calls);
    }
}
