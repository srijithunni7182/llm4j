package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.Media;
import io.github.llm4j.model.MediaCapabilities;
import io.github.llm4j.model.MediaKind;
import io.github.llm4j.model.MediaPolicy;
import io.github.llm4j.model.Message;
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
 * docs/specs/31-multimodal-input R13 and D7: an agent whose {@code guard} masks or blocks personal
 * data cannot vouch for a picture or a recording, so it refuses media unless the script says
 * {@code media: unscanned}.
 *
 * <p>Two layers say so. {@code weave check} refuses a script that names such an agent and attaches
 * to it (MediaChecksTest), and the load stops there. The runtime refuses too, for what the check
 * cannot see: an agent chosen at run time ({@code to {who}}), reached here, and the guard itself,
 * tested directly.
 */
class AttachGuardTest {

    @TempDir Path dir;

    private final List<LLMRequest> sent = Collections.synchronizedList(new ArrayList<>());

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
    }

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
                        return MediaCapabilities.of(MediaKind.IMAGE);
                    }
                };
    }

    private static String script(String guard, String steps) {
        return "media_root: \"assets\"\n"
                + "agent Reader { model: \"test\" system: \"x\" "
                + guard
                + " }\n"
                + "workflow Main(receipt, who) {\n"
                + steps
                + "\n}\n";
    }

    private HarnessExecutor executor(String source) {
        LoomScript script = new LoomParser(new Lexer(source).tokenize()).parseScript();
        HarnessExecutor executor = new HarnessExecutor(script, new ToolRegistry(), models());
        executor.setBaseDir(dir);
        executor.initialize();
        return executor;
    }

    /** Runs a step whose agent is named by an input, so the check cannot see which one. */
    private void runChosen(String guard, String text) {
        executor(script(guard, "delegate \"" + text + "\" to {who} attach image \"receipt.png\" -> out"))
                .executeWorkflow("Main", Map.of("receipt", "receipt", "who", "Reader"));
    }

    private static Message userMessageOf(LLMRequest request) {
        return request.getMessages().stream()
                .filter(m -> m.getRole() == Message.Role.USER)
                .findFirst()
                .orElseThrow();
    }

    private static Media photo() {
        return Media.of(MediaKind.IMAGE, png(), "a.png", MediaPolicy.defaults());
    }

    /** An executor for an agent with this guard and a step that attaches nothing, so it loads. */
    private HarnessExecutor guarded(String guard) {
        return executor(script(guard, "delegate \"hello\" to Reader -> out"));
    }

    // ── an agent chosen at run time: the runtime's own refusal ─────────────────────────────

    @Test
    void anAgentThatMasksPersonalDataRefusesMediaAndSendsNothing() {
        assertThatThrownBy(() -> runChosen("guard { pii: mask }", "mail ana@example.com"))
                .hasStackTraceContaining("agent Reader has guard { pii: mask }")
                .hasStackTraceContaining("media: unscanned");
        assertThat(sent).isEmpty();
    }

    @Test
    void anAgentThatBlocksPersonalDataRefusesMediaToo() {
        assertThatThrownBy(() -> runChosen("guard { pii: block }", "what is the total"))
                .hasStackTraceContaining("agent Reader has guard { pii: block }");
        assertThat(sent).isEmpty();
    }

    @Test
    void mediaUnscannedLetsTheMediaThroughAndTheTextIsStillMasked() {
        runChosen("guard { pii: mask  media: unscanned }", "mail ana@example.com about this");

        assertThat(sent).isNotEmpty();
        Message message = userMessageOf(sent.get(0));
        assertThat(message.getMedia()).hasSize(1);
        assertThat(message.getContent()).doesNotContain("ana@example.com").contains("[EMAIL]");
    }

    @Test
    void mediaUnscannedLetsABlockingAgentThrough() {
        runChosen("guard { pii: block  media: unscanned }", "what is the total");

        assertThat(userMessageOf(sent.get(0)).getMedia()).hasSize(1);
    }

    @Test
    void warningOnlyRecordsSoItDoesNotStopMedia() {
        runChosen("guard { pii: warn }", "what is the total");

        assertThat(userMessageOf(sent.get(0)).getMedia()).hasSize(1);
    }

    @Test
    void aGuardWithoutPiiDoesNotStopMedia() {
        runChosen("guard { bias: warn }", "what is the total");

        assertThat(userMessageOf(sent.get(0)).getMedia()).hasSize(1);
    }

    @Test
    void anAgentWithNoGuardDoesNotStopMedia() {
        runChosen("", "what is the total");

        assertThat(userMessageOf(sent.get(0)).getMedia()).hasSize(1);
    }

    @Test
    void textOnlyStepsOfAMaskingAgentAreNotAffected() {
        guarded("guard { pii: mask }")
                .executeWorkflow("Main", Map.of("receipt", "receipt", "who", "Reader"));

        assertThat(sent).isNotEmpty();
        assertThat(sent.get(0).mediaKinds()).isEmpty();
    }

    // ── the guard asked directly: what broadcast and handoff reach after the check ─────────

    @Test
    void theGuardRefusesMediaForAMaskingOrBlockingAgentWhoeverAsks() {
        assertThatThrownBy(() -> guarded("guard { pii: mask }").requireMediaFor("Reader", List.of(photo())))
                .isInstanceOf(MediaRefusal.class)
                .hasMessageContaining("pii: mask");
        assertThatThrownBy(() -> guarded("guard { pii: block }").requireMediaFor("Reader", List.of(photo())))
                .isInstanceOf(MediaRefusal.class)
                .hasMessageContaining("pii: block");
    }

    @Test
    void theGuardLetsMediaPassWhenTheScriptAcceptedItOrDoesNotMaskOrBlock() {
        assertThatCode(
                        () ->
                                guarded("guard { pii: mask  media: unscanned }")
                                        .requireMediaFor("Reader", List.of(photo())))
                .doesNotThrowAnyException();
        assertThatCode(() -> guarded("guard { pii: warn }").requireMediaFor("Reader", List.of(photo())))
                .doesNotThrowAnyException();
        assertThatCode(() -> guarded("").requireMediaFor("Reader", List.of(photo())))
                .doesNotThrowAnyException();
    }

    @Test
    void theGuardIsNotAskedAboutNothing() {
        assertThatCode(() -> guarded("guard { pii: mask }").requireMediaFor("Reader", List.of()))
                .doesNotThrowAnyException();
    }

    // ── the check refuses the same script at load, for a named agent ───────────────────────

    @Test
    void aScriptThatNamesAMaskingAgentAndAttachesToItDoesNotLoad() {
        for (String step :
                List.of(
                        "delegate \"x\" to Reader attach image \"receipt.png\" -> out",
                        "broadcast \"x\" to [Reader] attach image \"receipt.png\" -> out",
                        "handoff \"x\" to Reader attach image \"receipt.png\"")) {
            assertThatThrownBy(() -> executor(script("guard { pii: mask }", step)))
                    .hasStackTraceContaining("pii: mask")
                    .hasStackTraceContaining("media: unscanned");
        }
        assertThat(sent).isEmpty();
    }
}
