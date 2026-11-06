package io.github.llm4j.loom.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.ast.AttachItem;
import io.github.llm4j.loom.ast.BroadcastStmt;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.HandoffStmt;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.model.MediaKind;
import org.junit.jupiter.api.Test;

/**
 * docs/specs/31-multimodal-input R16 to R18: the {@code attach} clause on the statements that send
 * a task to an agent, and the script's {@code media_root} and {@code media { }} settings.
 */
class AttachParserTest {

    private static LoomScript parse(String source) {
        return new LoomParser(new Lexer(source).tokenize()).parseScript();
    }

    private static final String AGENTS =
            """
            agent Vision { model: "gemini-2.5-flash" system: "You read receipts." }
            agent Other { model: "gemini-2.5-flash" system: "x" }
            """;

    private static <T> T first(LoomScript script, Class<T> type) {
        return script.getWorkflows().get(0).getStatements().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst()
                .orElseThrow();
    }

    private static LoomScript workflow(String body) {
        return parse(AGENTS + "workflow Main(receipt) {\n" + body + "\n}\n");
    }

    // ── delegate ───────────────────────────────────────────────────────────────────────────

    @Test
    void delegateTakesAttachmentsAfterItsTargetAndBeforeTheArrow() {
        DelegateStmt del =
                first(
                        workflow(
                                "delegate \"What is the total?\" to Vision attach image \"{receipt}\" -> total"),
                        DelegateStmt.class);

        assertThat(del.getTargetAgent()).isEqualTo("Vision");
        assertThat(del.getVariableName()).isEqualTo("total");
        assertThat(del.getAttachments())
                .extracting(AttachItem::kind, AttachItem::path)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(MediaKind.IMAGE, "{receipt}"));
    }

    @Test
    void severalItemsAreCommaSeparatedAndKeepTheirOrderAndKinds() {
        DelegateStmt del =
                first(
                        workflow(
                                "delegate \"Compare\" to Vision attach image \"a.png\", audio \"call.wav\","
                                        + " video \"clip.mp4\", document \"policy.pdf\" -> out"),
                        DelegateStmt.class);

        assertThat(del.getAttachments())
                .extracting(AttachItem::kind)
                .containsExactly(
                        MediaKind.IMAGE, MediaKind.AUDIO, MediaKind.VIDEO, MediaKind.DOCUMENT);
        assertThat(del.getAttachments()).extracting(AttachItem::path)
                .containsExactly("a.png", "call.wav", "clip.mp4", "policy.pdf");
    }

    @Test
    void theRestOfTheStepStillParsesAfterAnAttachment() {
        DelegateStmt del =
                first(
                        workflow(
                                "delegate \"Total?\" to Vision attach image \"a.png\" -> total"
                                        + " expecting { amount: number } retry 2 timeout 30s"),
                        DelegateStmt.class);

        assertThat(del.getRetryCount()).isEqualTo(2);
        assertThat(del.getTimeoutMillis()).isEqualTo(30_000);
        assertThat(del.getExpecting()).isNotNull();
        assertThat(del.getAttachments()).hasSize(1);
    }

    @Test
    void aRunTimeTargetCanCarryAttachmentsToo() {
        DelegateStmt del =
                first(
                        workflow(
                                "delegate \"x\" to {task.owner} in [Vision, Other] attach image \"a.png\" ->"
                                        + " out"),
                        DelegateStmt.class);

        assertThat(del.getAllowedTargets()).containsExactly("Vision", "Other");
        assertThat(del.getAttachments()).hasSize(1);
    }

    @Test
    void aDelegateWithoutAttachmentsHasNone() {
        assertThat(first(workflow("delegate \"x\" to Vision -> out"), DelegateStmt.class).getAttachments())
                .isEmpty();
    }

    // ── broadcast and handoff ──────────────────────────────────────────────────────────────

    @Test
    void broadcastSendsTheSameAttachmentsToEveryAgent() {
        BroadcastStmt b =
                first(
                        workflow("broadcast \"look\" to [Vision, Other] attach image \"a.png\" -> out"),
                        BroadcastStmt.class);

        assertThat(b.getTargetAgents()).containsExactly("Vision", "Other");
        assertThat(b.getAttachments()).extracting(AttachItem::path).containsExactly("a.png");
    }

    @Test
    void handoffEndsWithItsAttachments() {
        HandoffStmt h =
                first(workflow("handoff \"look\" to Vision attach document \"a.pdf\""), HandoffStmt.class);

        assertThat(h.getTargetAgent()).isEqualTo("Vision");
        assertThat(h.getAttachments())
                .extracting(AttachItem::kind)
                .containsExactly(MediaKind.DOCUMENT);
    }

    // ── what is refused, and how it says so ────────────────────────────────────────────────

    @Test
    void anUnknownKindNamesTheFourThatExist() {
        assertThatThrownBy(() -> workflow("delegate \"x\" to Vision attach picture \"a.png\" -> out"))
                .hasMessageContaining("image, audio, video or document")
                .hasMessageContaining("picture");
    }

    @Test
    void aKindWithoutAFileSaysWhatToWrite() {
        assertThatThrownBy(() -> workflow("delegate \"x\" to Vision attach image -> out"))
                .hasMessageContaining("attach image \"photo.png\"");
    }

    @Test
    void aStatementThatSendsNothingToAModelExplainsWhereAttachBelongs() {
        assertThatThrownBy(() -> workflow("note \"hello\"\nattach image \"a.png\""))
                .hasMessageContaining("attach")
                .hasMessageContaining("delegate, broadcast, handoff");
    }

    // ── nothing existing breaks ────────────────────────────────────────────────────────────

    @Test
    void attachAndMediaStayUsableAsNamesOfAgentsAndVariables() {
        LoomScript script =
                parse(
                        """
                        agent attach { model: "gemini-2.5-flash" system: "x" }
                        workflow Main(media) {
                            delegate "hello" to attach -> image
                            delegate "again {image}" to attach -> media
                        }
                        """);

        DelegateStmt del = (DelegateStmt) script.getWorkflows().get(0).getStatements().get(0);
        assertThat(del.getTargetAgent()).isEqualTo("attach");
        assertThat(del.getVariableName()).isEqualTo("image");
        assertThat(del.getAttachments()).isEmpty();
    }

    // ── the script's media settings ────────────────────────────────────────────────────────

    @Test
    void mediaRootIsAScriptLevelSetting() {
        LoomScript script = parse("media_root: \"./assets\"\n" + AGENTS);

        assertThat(script.getMediaRoot()).isEqualTo("./assets");
    }

    @Test
    void aScriptWithoutOneHasNoRootAndNoLimitsOfItsOwn() {
        LoomScript script = parse(AGENTS);

        assertThat(script.getMediaRoot()).isNull();
        assertThat(script.getMediaMaxBytes()).isNull();
        assertThat(script.getMediaMaxItems()).isNull();
    }

    @Test
    void mediaLimitsTakeBytesWithAUnitAndAnItemCount() {
        LoomScript script = parse("media { max_bytes: 20MB  max_items: 3 }\n" + AGENTS);

        assertThat(script.getMediaMaxBytes()).isEqualTo(20L * 1024 * 1024);
        assertThat(script.getMediaMaxItems()).isEqualTo(3);
    }

    @Test
    void unitsAreKbMbGbOrNone() {
        assertThat(parse("media { max_bytes: 512KB }\n" + AGENTS).getMediaMaxBytes()).isEqualTo(512L * 1024);
        assertThat(parse("media { max_bytes: 2GB }\n" + AGENTS).getMediaMaxBytes()).isEqualTo(2L * 1024 * 1024 * 1024);
        assertThat(parse("media { max_bytes: 4096 }\n" + AGENTS).getMediaMaxBytes()).isEqualTo(4096L);
    }

    @Test
    void anUnknownMediaSettingListsTheOnesThatExist() {
        assertThatThrownBy(() -> parse("media { colour: red }\n" + AGENTS))
                .hasMessageContaining("max_bytes")
                .hasMessageContaining("max_items");
    }

    @Test
    void aBadSizeUnitIsRefused() {
        assertThatThrownBy(() -> parse("media { max_bytes: 5XB }\n" + AGENTS))
                .hasMessageContaining("KB, MB or GB");
    }

    @Test
    void thereIsOneMediaRootAndOneMediaBlock() {
        assertThatThrownBy(() -> parse("media_root: \"a\"\nmedia_root: \"b\"\n" + AGENTS))
                .hasMessageContaining("one media_root");
        assertThatThrownBy(() -> parse("media { max_items: 1 }\nmedia { max_items: 2 }\n" + AGENTS))
                .hasMessageContaining("one media block");
    }
}
