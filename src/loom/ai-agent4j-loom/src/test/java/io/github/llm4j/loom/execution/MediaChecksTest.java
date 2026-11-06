package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * docs/specs/31-multimodal-input R17: {@code weave check} finds what is wrong with a step's
 * attachments before anything runs, names the line, and says what to change. What cannot be known
 * until run time is said so, not guessed.
 */
class MediaChecksTest {

    @TempDir Path dir;

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
        Files.write(
                dir.resolve("assets/scan.png"),
                Arrays.copyOf("%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII), 64));
        Files.write(dir.resolve("secret.png"), png());
    }

    private static final String AGENTS =
            """
            agent Reader { model: "gemini-2.5-flash" system: "x" }
            agent Claude { model: "claude-sonnet-5-5" system: "x" }
            agent Masker { model: "gemini-2.5-flash" system: "x" guard { pii: mask } }
            agent Accepting { model: "gemini-2.5-flash" system: "x" guard { pii: mask  media: unscanned } }
            extractor Form { model: "gemini-2.5-flash" fields { total: number } }
            extractor MaskedForm { model: "gemini-2.5-flash" fields { total: number } pii: mask }
            extractor OkForm { model: "gemini-2.5-flash" fields { total: number } pii: mask  media: unscanned }
            """;

    private List<ScriptValidator.Problem> check(String header, String steps) {
        String source = header + AGENTS + "workflow Main(receipt) {\n" + steps + "\n}\n";
        return new ScriptValidator()
                .validate(
                        new LoomParser(new Lexer(source).tokenize()).parseScript(),
                        new ScriptValidator.Context().baseDir(dir).env(name -> "test"));
    }

    private List<String> errors(String header, String steps) {
        return check(header, steps).stream()
                .filter(p -> p.severity() == ScriptValidator.Severity.ERROR)
                .map(ScriptValidator.Problem::toString)
                .toList();
    }

    private List<String> warnings(String header, String steps) {
        return check(header, steps).stream()
                .filter(p -> p.severity() == ScriptValidator.Severity.WARNING)
                .map(ScriptValidator.Problem::toString)
                .toList();
    }

    private static final String ROOT = "media_root: \"assets\"\n";

    // ── a script that is right says nothing ────────────────────────────────────────────────

    @Test
    void aCorrectScriptHasNothingToReport() {
        assertThat(
                        errors(
                                ROOT,
                                "delegate \"total?\" to Reader attach image \"receipt.png\" -> out\n"
                                        + "extract Form from image \"receipt.png\" -> fields\n"
                                        + "broadcast \"look\" to [Reader] attach image \"receipt.png\" -> all\n"
                                        + "handoff \"look\" to Reader attach image \"receipt.png\""))
                .isEmpty();
    }

    @Test
    void aScriptThatAttachesNothingIsNotAskedForARoot() {
        assertThat(errors("", "delegate \"hello\" to Reader -> out")).isEmpty();
    }

    // ── the media folder ───────────────────────────────────────────────────────────────────

    @Test
    void attachingWithoutAMediaRootIsAnErrorThatSaysWhatToAdd() {
        assertThat(errors("", "delegate \"x\" to Reader attach image \"receipt.png\" -> out"))
                .anySatisfy(
                        e -> assertThat(e).contains("media_root").contains("media_root: \"./assets\""));
    }

    @Test
    void theFolderIsCheckedEvenWhenEveryFileNameIsFilledInAtRunTime() {
        // nothing literal to load, so only the folder check can see these
        assertThat(errors("", "delegate \"x\" to Reader attach image \"{receipt}.png\" -> out"))
                .anySatisfy(e -> assertThat(e).contains("media_root"));
        assertThat(
                        errors(
                                "media_root: \"nowhere\"\n",
                                "delegate \"x\" to Reader attach image \"{receipt}.png\" -> out"))
                .anySatisfy(e -> assertThat(e).contains("nowhere"));
        assertThat(
                        errors(
                                "media_root: \"..\"\n",
                                "extract Form from image \"{receipt}.png\" -> fields"))
                .anySatisfy(e -> assertThat(e).contains("inside the script"));
    }

    @Test
    void anExtractWithoutAMediaRootIsTheSame() {
        assertThat(errors("", "extract Form from image \"receipt.png\" -> fields"))
                .anySatisfy(e -> assertThat(e).contains("media_root"));
    }

    @Test
    void aMediaRootThatDoesNotExistIsAnError() {
        assertThat(
                        errors(
                                "media_root: \"nowhere\"\n",
                                "delegate \"x\" to Reader attach image \"receipt.png\" -> out"))
                .anySatisfy(e -> assertThat(e).contains("media_root").contains("nowhere"));
    }

    @Test
    void aMediaRootOutsideTheScriptsFolderIsAnError() {
        assertThat(
                        errors(
                                "media_root: \"..\"\n",
                                "delegate \"x\" to Reader attach image \"receipt.png\" -> out"))
                .anySatisfy(e -> assertThat(e).contains("inside the script"));
    }

    // ── the files ──────────────────────────────────────────────────────────────────────────

    @Test
    void aMissingFileIsAnErrorOnItsLine() {
        assertThat(errors(ROOT, "delegate \"x\" to Reader attach image \"missing.png\" -> out"))
                .anySatisfy(e -> assertThat(e).contains("missing.png").matches("(?s)line \\d+: .*"));
    }

    @Test
    void aPathThatLeavesTheFolderIsAnError() {
        assertThat(errors(ROOT, "delegate \"x\" to Reader attach image \"../secret.png\" -> out"))
                .anySatisfy(e -> assertThat(e).contains("attach image"));
    }

    @Test
    void aFileThatIsNotWhatItIsCalledIsAnError() {
        assertThat(errors(ROOT, "delegate \"x\" to Reader attach image \"scan.png\" -> out"))
                .anySatisfy(e -> assertThat(e).contains("given as IMAGE"));
    }

    @Test
    void aPathFilledInAtRunTimeIsAWarningSaidPlainly() {
        assertThat(errors(ROOT, "delegate \"x\" to Reader attach image \"{receipt}.png\" -> out")).isEmpty();
        assertThat(warnings(ROOT, "delegate \"x\" to Reader attach image \"{receipt}.png\" -> out"))
                .anySatisfy(w -> assertThat(w).contains("filled in at run time").contains("not checked"));
    }

    @Test
    void moreItemsThanTheScriptAllowsIsAnError() {
        assertThat(
                        errors(
                                "media { max_items: 1 }\n" + ROOT,
                                "delegate \"x\" to Reader attach image \"receipt.png\", image \"receipt.png\" -> out"))
                .anySatisfy(e -> assertThat(e).contains("2 attachments").contains("max_items"));
    }

    @Test
    void aProblemInANestedBlockIsFoundToo() {
        assertThat(
                        errors(
                                ROOT,
                                "alt (receipt == \"a\") {\n"
                                        + "  delegate \"x\" to Reader attach image \"missing.png\" -> out\n"
                                        + "} else {\n"
                                        + "  note \"n\"\n"
                                        + "}"))
                .anySatisfy(e -> assertThat(e).contains("missing.png"));
    }

    // ── what the model can take ────────────────────────────────────────────────────────────

    @Test
    void aKindTheAgentsModelCannotTakeIsAnErrorNamingTheAgentAndWhatItAccepts() throws IOException {
        Files.write(dir.resolve("assets/clip.mp4"), mp4());

        assertThat(errors(ROOT, "delegate \"x\" to Claude attach video \"clip.mp4\" -> out"))
                .anySatisfy(
                        e ->
                                assertThat(e)
                                        .contains("agent Claude (claude-sonnet-5-5)")
                                        .contains("does not accept video")
                                        .contains("accepted: image, document"));
    }

    @Test
    void aModelThatTakesTheKindIsFine() throws IOException {
        Files.write(dir.resolve("assets/clip.mp4"), mp4());

        assertThat(errors(ROOT, "delegate \"x\" to Reader attach video \"clip.mp4\" -> out")).isEmpty();
    }

    @Test
    void everyTargetOfABroadcastIsChecked() throws IOException {
        Files.write(dir.resolve("assets/clip.mp4"), mp4());

        assertThat(errors(ROOT, "broadcast \"x\" to [Reader, Claude] attach video \"clip.mp4\" -> out"))
                .hasSize(1)
                .anySatisfy(e -> assertThat(e).contains("Claude"));
    }

    @Test
    void aHandoffTargetIsChecked() throws IOException {
        Files.write(dir.resolve("assets/clip.mp4"), mp4());

        assertThat(errors(ROOT, "handoff \"x\" to Claude attach video \"clip.mp4\""))
                .anySatisfy(e -> assertThat(e).contains("does not accept video"));
    }

    @Test
    void anExtractorsModelIsChecked() throws IOException {
        Files.write(dir.resolve("assets/clip.mp4"), mp4());
        String source =
                ROOT
                        + "extractor ClaudeForm { model: \"claude-sonnet-5-5\" fields { total: number } }\n"
                        + AGENTS
                        + "workflow Main(receipt) {\n extract ClaudeForm from video \"clip.mp4\" -> f\n}\n";
        List<String> errors =
                new ScriptValidator()
                        .validate(
                                new LoomParser(new Lexer(source).tokenize()).parseScript(),
                                new ScriptValidator.Context().baseDir(dir).env(name -> "test"))
                        .stream()
                        .filter(p -> p.severity() == ScriptValidator.Severity.ERROR)
                        .map(ScriptValidator.Problem::toString)
                        .toList();

        assertThat(errors).anySatisfy(e -> assertThat(e).contains("extractor ClaudeForm").contains("video"));
    }

    @Test
    void aTargetChosenAtRunTimeIsCheckedAgainstEachAgentItMayChoose() throws IOException {
        Files.write(dir.resolve("assets/clip.mp4"), mp4());

        assertThat(
                        errors(
                                ROOT,
                                "delegate \"x\" to {receipt} in [Reader, Claude] attach video \"clip.mp4\" -> out"))
                .anySatisfy(e -> assertThat(e).contains("Claude"));
    }

    @Test
    void aTargetChosenAtRunTimeWithNoListCannotBeCheckedAndSaysSo() {
        assertThat(warnings(ROOT, "delegate \"x\" to {receipt} attach image \"receipt.png\" -> out"))
                .anySatisfy(w -> assertThat(w).contains("chosen at run time").contains("not checked"));
    }

    @Test
    void aModelNobodyCanNameFromItsStringIsNotGuessedAbout() {
        String source =
                ROOT
                        + "provider Box { use: openai_compatible base_url: \"http://localhost:1/v1\" }\n"
                        + "agent Local { model: \"Box/whatever\" system: \"x\" }\n"
                        + "workflow Main(receipt) {\n delegate \"x\" to Local attach image \"receipt.png\" -> out\n}\n";
        List<ScriptValidator.Problem> problems =
                new ScriptValidator()
                        .validate(
                                new LoomParser(new Lexer(source).tokenize()).parseScript(),
                                new ScriptValidator.Context().baseDir(dir).env(name -> "test"));

        assertThat(problems)
                .noneSatisfy(p -> assertThat(p.message()).contains("does not accept"));
    }

    // ── personal data ──────────────────────────────────────────────────────────────────────

    @Test
    void anAgentThatMasksPersonalDataCannotBeGivenMediaUnlessItAccepts() {
        assertThat(errors(ROOT, "delegate \"x\" to Masker attach image \"receipt.png\" -> out"))
                .anySatisfy(e -> assertThat(e).contains("pii: mask").contains("media: unscanned"));
        assertThat(errors(ROOT, "delegate \"x\" to Accepting attach image \"receipt.png\" -> out")).isEmpty();
    }

    @Test
    void anExtractorThatMasksPersonalDataIsTheSame() {
        assertThat(errors(ROOT, "extract MaskedForm from image \"receipt.png\" -> f"))
                .anySatisfy(e -> assertThat(e).contains("pii: mask").contains("media: unscanned"));
        assertThat(errors(ROOT, "extract OkForm from image \"receipt.png\" -> f")).isEmpty();
    }

    // ── the guard setting itself ───────────────────────────────────────────────────────────

    /** The full check, including the agent-level settings that only the executor's context knows. */
    private List<ScriptValidator.Problem> checkScript(String source) {
        var script = new LoomParser(new Lexer(source).tokenize()).parseScript();
        HarnessExecutor executor =
                new HarnessExecutor(
                        script,
                        new ToolRegistry(),
                        model -> {
                            throw new UnsupportedOperationException("checking calls no model");
                        });
        executor.setBaseDir(dir);
        return new ScriptValidator().validate(script, executor.validationContext().env(name -> "test"));
    }

    @Test
    void theGuardsMediaSettingTakesOnlyUnscanned() {
        List<String> errors =
                checkScript("agent A { model: \"gemini-2.5-flash\" system: \"x\" guard { pii: mask  media: scanned } }\n"
                                + "workflow Main() { note \"n\" }")
                        .stream()
                        .filter(p -> p.severity() == ScriptValidator.Severity.ERROR)
                        .map(ScriptValidator.Problem::toString)
                        .toList();

        assertThat(errors).anySatisfy(e -> assertThat(e).contains("media: unscanned"));
    }

    @Test
    void unscannedMediaDoesNothingWithoutMaskingOrBlocking() {
        List<String> warnings =
                checkScript("agent A { model: \"gemini-2.5-flash\" system: \"x\" guard { pii: warn  media: unscanned } }\n"
                                + "workflow Main() { note \"n\" }")
                        .stream()
                        .filter(p -> p.severity() == ScriptValidator.Severity.WARNING)
                        .map(ScriptValidator.Problem::toString)
                        .toList();

        assertThat(warnings).anySatisfy(w -> assertThat(w).contains("does nothing unless pii is mask or block"));
    }

    @Test
    void unscannedMediaWithMaskingIsAccepted() {
        assertThat(
                        checkScript("agent A { model: \"gemini-2.5-flash\" system: \"x\" guard { pii: mask  media: unscanned } }\n"
                                        + "workflow Main() { note \"n\" }"))
                .noneSatisfy(p -> assertThat(p.message()).contains("media"));
    }

    private static byte[] mp4() {
        byte[] bytes = new byte[64];
        System.arraycopy("ftypisom".getBytes(StandardCharsets.US_ASCII), 0, bytes, 4, 8);
        return bytes;
    }
}
