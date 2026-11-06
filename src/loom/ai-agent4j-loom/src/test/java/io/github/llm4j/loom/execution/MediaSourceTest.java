package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.ast.AttachItem;
import io.github.llm4j.model.Media;
import io.github.llm4j.model.MediaKind;
import io.github.llm4j.model.MediaPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * docs/specs/31-multimodal-input R16 and R18: where a script's attachments may come from. A path is
 * an input from outside, so every way out of the media folder is tested, and a guard is only
 * trusted once removing it fails a test.
 */
class MediaSourceTest {

    @TempDir Path script;

    private Path assets;
    private static final UnaryOperator<String> AS_WRITTEN = s -> s;

    /** The first bytes of a PNG: enough for the library to know what it is. */
    private static byte[] png() {
        byte[] bytes = new byte[64];
        byte[] magic = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(magic, 0, bytes, 0, magic.length);
        return bytes;
    }

    private static byte[] pdf() {
        return Arrays.copyOf("%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII), 64);
    }

    @BeforeEach
    void folders() throws IOException {
        assets = Files.createDirectories(script.resolve("assets"));
        Files.write(assets.resolve("receipt.png"), png());
    }

    private MediaSource source(String root) {
        return new MediaSource(script, root, Set.of(), MediaPolicy.defaults());
    }

    private static AttachItem image(String path) {
        return new AttachItem(MediaKind.IMAGE, path, 1);
    }

    private List<Media> load(MediaSource source, String... paths) {
        return source.load(
                Arrays.stream(paths).map(MediaSourceTest::image).toList(), AS_WRITTEN);
    }

    // ── the happy path ─────────────────────────────────────────────────────────────────────

    @Test
    void loadsAFileUnderTheMediaRoot() {
        List<Media> media = load(source("./assets"), "receipt.png");

        assertThat(media).hasSize(1);
        assertThat(media.get(0).kind()).isEqualTo(MediaKind.IMAGE);
        assertThat(media.get(0).mediaType()).isEqualTo("image/png");
        assertThat(media.get(0).name()).isEqualTo("receipt.png");
    }

    @Test
    void fillsInVariablesBeforeLookingForTheFile() {
        List<Media> media =
                source("assets")
                        .load(List.of(image("{name}.png")), s -> s.replace("{name}", "receipt"));

        assertThat(media).hasSize(1);
    }

    @Test
    void aFileInASubfolderIsFine() throws IOException {
        Files.createDirectories(assets.resolve("2026/11"));
        Files.write(assets.resolve("2026/11/a.png"), png());

        assertThat(load(source("assets"), "2026/11/a.png")).hasSize(1);
    }

    @Test
    void nothingToAttachNeedsNoRoot() {
        assertThat(source(null).load(List.of(), AS_WRITTEN)).isEmpty();
    }

    // ── the media root ─────────────────────────────────────────────────────────────────────

    @Test
    void refusesFilesWhenTheScriptNamedNoRoot() {
        assertThatThrownBy(() -> load(source(null), "receipt.png"))
                .isInstanceOf(MediaRefusal.class)
                .hasMessageContaining("media_root");
    }

    @Test
    void refusesARootThatDoesNotExist() {
        assertThatThrownBy(() -> load(source("nowhere"), "receipt.png"))
                .isInstanceOf(MediaRefusal.class)
                .hasMessageContaining("media_root")
                .hasMessageContaining("nowhere");
    }

    @Test
    void refusesARootThatIsAFile() throws IOException {
        Files.write(script.resolve("afile"), png());

        assertThatThrownBy(() -> load(source("afile"), "receipt.png"))
                .isInstanceOf(MediaRefusal.class)
                .hasMessageContaining("folder");
    }

    @ParameterizedTest
    @ValueSource(strings = {"..", "../elsewhere", "/", "/tmp", "assets/../.."})
    void refusesARootOutsideTheScriptsFolder(String root) {
        assertThatThrownBy(() -> load(source(root), "receipt.png"))
                .isInstanceOf(MediaRefusal.class)
                .hasMessageContaining("inside the script");
    }

    @Test
    void refusesARootThatIsALinkOutOfTheScriptsFolder(@TempDir Path elsewhere) throws IOException {
        Files.write(elsewhere.resolve("secret.png"), png());
        Files.createSymbolicLink(script.resolve("linked"), elsewhere);

        assertThatThrownBy(() -> load(source("linked"), "secret.png"))
                .isInstanceOf(MediaRefusal.class);
    }

    // ── every way out of the root ──────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(
            strings = {
                "../outside.png",
                "../../etc/passwd",
                "sub/../../outside.png",
                "/etc/passwd",
                "/tmp/outside.png",
                "assets/../../outside.png"
            })
    void refusesAPathThatLeavesTheRoot(String path) throws IOException {
        Files.write(script.resolve("outside.png"), png());

        assertThatThrownBy(() -> load(source("assets"), path)).isInstanceOf(MediaRefusal.class);
    }

    @Test
    void refusesALinkInsideTheRootThatLeadsOut(@TempDir Path elsewhere) throws IOException {
        Files.write(elsewhere.resolve("secret.png"), png());
        Files.createSymbolicLink(assets.resolve("escape"), elsewhere);

        assertThatThrownBy(() -> load(source("assets"), "escape/secret.png"))
                .isInstanceOf(MediaRefusal.class);
    }

    @Test
    void refusesALinkedFileThatLeadsOut(@TempDir Path elsewhere) throws IOException {
        Path secret = Files.write(elsewhere.resolve("secret.png"), png());
        Files.createSymbolicLink(assets.resolve("looks-innocent.png"), secret);

        assertThatThrownBy(() -> load(source("assets"), "looks-innocent.png"))
                .isInstanceOf(MediaRefusal.class);
    }

    @Test
    void refusesAPathBuiltFromAVariableThatNamesAnEscape() {
        // a model's answer or a user's input fills the variable
        assertThatThrownBy(
                        () ->
                                source("assets")
                                        .load(List.of(image("{file}")), s -> s.replace("{file}", "../outside.png")))
                .isInstanceOf(MediaRefusal.class);
    }

    @Test
    void refusesHiddenFilesAndFolders() throws IOException {
        Files.write(assets.resolve(".hidden.png"), png());
        Files.createDirectories(assets.resolve(".git"));
        Files.write(assets.resolve(".git/a.png"), png());

        assertThatThrownBy(() -> load(source("assets"), ".hidden.png")).isInstanceOf(MediaRefusal.class);
        assertThatThrownBy(() -> load(source("assets"), ".git/a.png")).isInstanceOf(MediaRefusal.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"re\nceipt.png", "re\u0000ceipt.png", "receipt.png\r", "a b.png"})
    void refusesAPathWithAControlOrLineBreakCharacter(String path) {
        assertThatThrownBy(() -> load(source("assets"), path))
                .isInstanceOf(MediaRefusal.class)
                .hasMessageContaining("control")
                // the message goes into logs: it must not carry the line break it complains about
                .satisfies(e -> assertThat(e.getMessage()).doesNotContainPattern("[\\p{Cntrl}\\u2028\\u2029]"));
    }

    @Test
    void refusesWhatBelongsToTheRunItself() throws IOException {
        Path journal = Files.write(assets.resolve("run.journal"), png());
        MediaSource guarded =
                new MediaSource(
                        script, "assets", Set.of(journal.toAbsolutePath().normalize()), MediaPolicy.defaults());

        assertThatThrownBy(() -> load(guarded, "run.journal")).isInstanceOf(MediaRefusal.class);
    }

    // ── the file itself ────────────────────────────────────────────────────────────────────

    @Test
    void saysSoWhenTheFileIsMissingAndNamesItAsWritten() {
        assertThatThrownBy(() -> load(source("assets"), "missing.png"))
                .isInstanceOf(MediaRefusal.class)
                .hasMessageContaining("missing.png")
                .hasMessageContaining("retr");
    }

    @Test
    void refusesAFolderInPlaceOfAFile() throws IOException {
        Files.createDirectories(assets.resolve("pictures"));

        assertThatThrownBy(() -> load(source("assets"), "pictures")).isInstanceOf(MediaRefusal.class);
    }

    @Test
    void refusesAFileThatIsNotWhatTheScriptCalledIt() throws IOException {
        Files.write(assets.resolve("scan.png"), pdf());

        assertThatThrownBy(() -> load(source("assets"), "scan.png"))
                .isInstanceOf(MediaRefusal.class)
                .hasMessageContaining("given as IMAGE");
    }

    @Test
    void holdsFilesToTheScriptsSizeLimit() {
        MediaSource small =
                new MediaSource(
                        script, "assets", Set.of(), MediaPolicy.builder().maxBytesPerItem(10).build());

        assertThatThrownBy(() -> load(small, "receipt.png"))
                .isInstanceOf(MediaRefusal.class)
                .hasMessageContaining("10");
    }

    @Test
    void refusesMoreItemsThanTheScriptAllowsBeforeReadingAny() {
        MediaSource two =
                new MediaSource(
                        script, "assets", Set.of(), MediaPolicy.builder().maxItemsPerMessage(2).build());

        assertThatThrownBy(() -> load(two, "receipt.png", "receipt.png", "receipt.png"))
                .isInstanceOf(MediaRefusal.class)
                .hasMessageContaining("3 attachments")
                .hasMessageContaining("2");
    }

    @Test
    void refusesMoreBytesInTotalThanAMessageMayCarry() {
        MediaSource tight =
                new MediaSource(
                        script, "assets", Set.of(), MediaPolicy.builder().maxBytesPerRequest(100).build());

        assertThatThrownBy(() -> load(tight, "receipt.png", "receipt.png"))
                .isInstanceOf(MediaRefusal.class)
                .hasMessageContaining("128")
                .hasMessageContaining("100");
    }

    // ── what the messages leave out ────────────────────────────────────────────────────────

    @Test
    void aRefusalNeverShowsAnAbsolutePathOrTheFilesBytes() throws IOException {
        Files.write(assets.resolve("scan.png"), pdf());

        assertThatThrownBy(() -> load(source("assets"), "scan.png"))
                .satisfies(
                        e ->
                                assertThat(e.getMessage())
                                        .doesNotContain(script.toString())
                                        .doesNotContain("%PDF"));
        assertThatThrownBy(() -> load(source("assets"), "../outside.png"))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(script.toString()));
    }
}
