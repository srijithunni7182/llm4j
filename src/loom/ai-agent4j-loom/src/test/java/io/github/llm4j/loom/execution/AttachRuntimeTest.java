package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.MediaCapabilities;
import io.github.llm4j.model.MediaKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * docs/specs/31-multimodal-input R16 to R18: a step's attachments reach the agent's model with the
 * task, and any problem with them stops the step before a model is called.
 */
class AttachRuntimeTest {

    @TempDir Path dir;

    private final List<LLMRequest> sent = Collections.synchronizedList(new ArrayList<>());
    private final List<TraceEvent> trace = Collections.synchronizedList(new ArrayList<>());
    private MediaCapabilities takes = MediaCapabilities.of(MediaKind.IMAGE, MediaKind.DOCUMENT);

    private static byte[] png() {
        byte[] bytes = new byte[64];
        byte[] magic = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(magic, 0, bytes, 0, magic.length);
        return bytes;
    }

    @BeforeEach
    void assets() throws IOException {
        Files.createDirectories(dir.resolve("assets"));
        Files.write(dir.resolve("assets/receipt.png"), png());
        Files.write(dir.resolve("assets/other.png"), png());
    }

    /** A model that answers in the text protocol and records every request. */
    private LLMClientFactory models() {
        return model ->
                new LLMClient() {
                    @Override
                    public LLMResponse chat(LLMRequest request) {
                        sent.add(request);
                        return LLMResponse.builder()
                                .content("```json\n{\"thought\":\"done\",\"final_answer\":\"42\"}\n```")
                                .model(model)
                                .build();
                    }

                    @Override
                    public Stream<LLMResponse> chatStream(LLMRequest request) {
                        return Stream.of(chat(request));
                    }

                    @Override
                    public MediaCapabilities mediaCapabilities(String m) {
                        return takes;
                    }
                };
    }

    private HarnessExecutor executor(String source) {
        LoomScript script = new LoomParser(new Lexer(source).tokenize()).parseScript();
        HarnessExecutor executor = new HarnessExecutor(script, new ToolRegistry(), models());
        executor.setBaseDir(dir);
        executor.addTraceListener(trace::add);
        executor.initialize();
        return executor;
    }

    private static String script(String root, String steps) {
        return (root == null ? "" : "media_root: \"" + root + "\"\n")
                + """
                agent Reader { model: "test" system: "You read receipts." }
                agent Auditor { model: "test" system: "You audit." }
                workflow Main(receipt) {
                """
                + steps
                + "\n}\n";
    }

    private void run(String source) {
        executor(source).executeWorkflow("Main", Map.of("receipt", "receipt"));
    }

    private static io.github.llm4j.model.Message userMessageOf(LLMRequest request) {
        return request.getMessages().stream()
                .filter(m -> m.getRole() == io.github.llm4j.model.Message.Role.USER)
                .findFirst()
                .orElseThrow();
    }

    // ── delegate ───────────────────────────────────────────────────────────────────────────

    @Test
    void theModelIsSentTheFileWithTheTask() {
        run(script("./assets", "delegate \"What is the total?\" to Reader attach image \"receipt.png\" -> total"));

        assertThat(sent).hasSize(1);
        var message = userMessageOf(sent.get(0));
        assertThat(message.getContent()).contains("What is the total?");
        assertThat(message.getMedia()).hasSize(1);
        assertThat(message.getMedia().get(0).kind()).isEqualTo(MediaKind.IMAGE);
        assertThat(message.getMedia().get(0).name()).isEqualTo("receipt.png");
    }

    @Test
    void aVariableFillsInTheFileName() {
        run(script("assets", "delegate \"Total?\" to Reader attach image \"{receipt}.png\" -> total"));

        assertThat(userMessageOf(sent.get(0)).getMedia().get(0).name()).isEqualTo("receipt.png");
    }

    @Test
    void severalAttachmentsAllArrive() {
        run(script("assets", "delegate \"Compare\" to Reader attach image \"receipt.png\", image \"other.png\" -> out"));

        assertThat(userMessageOf(sent.get(0)).getMedia()).hasSize(2);
    }

    @Test
    void eachStepSendsItsOwnFiles() {
        run(
                script(
                        "assets",
                        "delegate \"one\" to Reader attach image \"receipt.png\" -> a\n"
                                + "delegate \"two\" to Reader attach image \"other.png\" -> b"));

        assertThat(sent).hasSize(2);
        assertThat(userMessageOf(sent.get(0)).getMedia().get(0).name()).isEqualTo("receipt.png");
        assertThat(userMessageOf(sent.get(1)).getMedia().get(0).name()).isEqualTo("other.png");
    }

    @Test
    void aStepWithoutAttachmentsSendsNoMedia() {
        run(script(null, "delegate \"hello\" to Reader -> out"));

        assertThat(sent).isNotEmpty().allSatisfy(r -> assertThat(r.mediaKinds()).isEmpty());
    }

    // ── nothing is sent when something is wrong ────────────────────────────────────────────

    @Test
    void noMediaRootStopsTheStepBeforeAnyModelIsCalled() {
        assertThatThrownBy(() -> run(script(null, "delegate \"x\" to Reader attach image \"receipt.png\" -> out")))
                .hasStackTraceContaining("media_root");
        assertThat(sent).isEmpty();
    }

    @Test
    void aPathThatEscapesTheFolderStopsTheStep() throws IOException {
        Files.write(dir.resolve("secret.png"), png());

        assertThatThrownBy(() -> run(script("assets", "delegate \"x\" to Reader attach image \"../secret.png\" -> out")))
                .hasStackTraceContaining("attach image");
        assertThat(sent).isEmpty();
    }

    @Test
    void aMissingFileStopsTheStepAndIsNotRetried() {
        assertThatThrownBy(
                        () ->
                                run(
                                        script(
                                                "assets",
                                                "delegate \"x\" to Reader attach image \"missing.png\" -> out retry 3")))
                .hasStackTraceContaining("missing.png")
                .hasStackTraceContaining("is not retried");
        // no attempt reached a model, however many retries the step allows
        assertThat(sent).isEmpty();
    }

    @Test
    void aKindTheModelCannotTakeStopsTheStepNamingTheAgent() throws IOException {
        Files.write(dir.resolve("assets/clip.mp4"), mp4());

        assertThatThrownBy(() -> run(script("assets", "delegate \"x\" to Reader attach video \"clip.mp4\" -> out")))
                .hasStackTraceContaining("agent Reader")
                .hasStackTraceContaining("does not accept video")
                .hasStackTraceContaining("accepted: image, document");
        assertThat(sent).isEmpty();
    }

    @Test
    void aModelThatTakesNothingSaysSo() {
        takes = MediaCapabilities.NONE;

        assertThatThrownBy(() -> run(script("assets", "delegate \"x\" to Reader attach image \"receipt.png\" -> out")))
                .hasStackTraceContaining("it takes no media");
        assertThat(sent).isEmpty();
    }

    @Test
    void theStepsOnFailureBlockRunsInsteadOfFailingTheWorkflow() {
        run(
                script(
                        "assets",
                        "delegate \"x\" to Reader attach image \"missing.png\" -> out on_failure { note \"handled\" }\n"
                                + "note \"after\""));

        assertThat(trace).anySatisfy(e -> assertThat(String.valueOf(e)).contains("handled"));
        assertThat(sent).isEmpty();
    }

    // ── the script's own limits ────────────────────────────────────────────────────────────

    @Test
    void aTighterLimitInTheScriptRefusesTheStep() {
        String source =
                "media { max_items: 1 }\n"
                        + script("assets", "delegate \"x\" to Reader attach image \"receipt.png\", image \"other.png\" -> out");

        assertThatThrownBy(() -> run(source))
                .hasStackTraceContaining("2 attachments")
                .hasStackTraceContaining("max_items");
        assertThat(sent).isEmpty();
    }

    @Test
    void aSmallerSizeLimitInTheScriptRefusesAFileThatIsOverIt() {
        String source =
                "media { max_bytes: 10 }\n"
                        + script("assets", "delegate \"x\" to Reader attach image \"receipt.png\" -> out");

        assertThatThrownBy(() -> run(source)).hasStackTraceContaining("10");
        assertThat(sent).isEmpty();
    }

    @Test
    void aLooserLimitInTheScriptLetsMoreThanTheDefaultFourThrough() throws IOException {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            Files.write(dir.resolve("assets/p" + i + ".png"), png());
            items.append(i == 0 ? "" : ", ").append("image \"p").append(i).append(".png\"");
        }
        String source =
                "media { max_items: 5 }\n"
                        + script("assets", "delegate \"x\" to Reader attach " + items + " -> out");

        run(source);

        // the agent's own request accepts them too, not only the step that loaded them
        assertThat(userMessageOf(sent.get(0)).getMedia()).hasSize(5);
    }

    // ── what is written down ───────────────────────────────────────────────────────────────

    @Test
    void theTraceNamesTheAttachmentsAndNeverCarriesTheirBytes() {
        run(script("assets", "delegate \"Total?\" to Reader attach image \"receipt.png\" -> total"));

        TraceEvent start =
                trace.stream()
                        .filter(e -> TraceEvent.DELEGATE_START.equals(e.type()))
                        .findFirst()
                        .orElseThrow();
        String data = String.valueOf(start.data());
        assertThat(data).contains("attachments").contains("image/png").contains("receipt.png").contains("sha256");
        assertThat(data).doesNotContain(java.util.Base64.getEncoder().encodeToString(png()));
    }

    // ── broadcast and handoff ──────────────────────────────────────────────────────────────

    @Test
    void broadcastSendsTheSameFileToEveryAgent() {
        run(script("assets", "broadcast \"look\" to [Reader, Auditor] attach image \"receipt.png\" -> both"));

        assertThat(sent).hasSize(2);
        assertThat(sent).allSatisfy(r -> assertThat(userMessageOf(r).getMedia()).hasSize(1));
    }

    @Test
    void handoffSendsTheFileToo() {
        run(script("assets", "handoff \"look\" to Reader attach image \"receipt.png\""));

        assertThat(sent).hasSize(1);
        assertThat(userMessageOf(sent.get(0)).getMedia()).hasSize(1);
    }

    @Test
    void broadcastAndHandoffRefuseWhatTheModelCannotTakeToo() {
        takes = MediaCapabilities.NONE;

        assertThatThrownBy(() -> run(script("assets", "broadcast \"look\" to [Reader] attach image \"receipt.png\" -> out")))
                .hasStackTraceContaining("does not accept image");
        assertThatThrownBy(() -> run(script("assets", "handoff \"look\" to Reader attach image \"receipt.png\"")))
                .hasStackTraceContaining("does not accept image");
        assertThat(sent).isEmpty();
    }

    private static byte[] mp4() {
        byte[] bytes = new byte[64];
        System.arraycopy("ftypisom".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, bytes, 4, 8);
        return bytes;
    }
}
