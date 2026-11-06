package io.github.llm4j.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.exception.InvalidRequestException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * docs/specs/31-multimodal-input R7, R8, R9 and R15: a {@link Media} is decided by its bytes,
 * bounded, immutable, and never prints or serialises its content.
 */
class MediaTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final MediaPolicy POLICY = MediaPolicy.defaults();

    static Stream<Arguments> everyKnownFormat() {
        return Stream.of(
                Arguments.of(MediaKind.IMAGE, TinyMedia.png(), "image/png"),
                Arguments.of(MediaKind.IMAGE, TinyMedia.jpeg(), "image/jpeg"),
                Arguments.of(MediaKind.IMAGE, TinyMedia.gif(), "image/gif"),
                Arguments.of(MediaKind.IMAGE, TinyMedia.webp(), "image/webp"),
                Arguments.of(MediaKind.DOCUMENT, TinyMedia.pdf(), "application/pdf"),
                Arguments.of(MediaKind.AUDIO, TinyMedia.wav(), "audio/wav"),
                Arguments.of(MediaKind.AUDIO, TinyMedia.mp3(), "audio/mpeg"),
                Arguments.of(MediaKind.AUDIO, TinyMedia.mp3WithId3(), "audio/mpeg"),
                Arguments.of(MediaKind.AUDIO, TinyMedia.m4a(), "audio/mp4"),
                Arguments.of(MediaKind.VIDEO, TinyMedia.mp4(), "video/mp4"),
                Arguments.of(MediaKind.VIDEO, TinyMedia.webm(), "video/webm"));
    }

    @ParameterizedTest
    @MethodSource("everyKnownFormat")
    void decidesTheTypeFromTheBytesWhateverTheNameSays(
            MediaKind kind, byte[] bytes, String mediaType) {
        Media media = Media.of(kind, bytes, "named-like-a-script.sh", POLICY);

        assertThat(media.kind()).isEqualTo(kind);
        assertThat(media.mediaType()).isEqualTo(mediaType);
    }

    @Test
    void refusesAKindThatIsNotWhatTheBytesAre() {
        // a PDF handed over as a picture: never trust the label
        assertThatThrownBy(() -> Media.of(MediaKind.IMAGE, TinyMedia.pdf(), "scan.png", POLICY))
                .isInstanceOf(InvalidRequestException.class)
                // the kind check speaks first; the allow-list behind it would only say "not
                // allowed"
                .hasMessageContaining("given as IMAGE but the bytes are application/pdf");
    }

    @Test
    void refusesSvgBecauseItCarriesScript() {
        assertThatThrownBy(() -> Media.of(MediaKind.IMAGE, TinyMedia.svg(), "logo.svg", POLICY))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("not a supported");
    }

    @Test
    void refusesAFormatItDoesNotKnow() {
        assertThatThrownBy(
                        () -> Media.of(MediaKind.IMAGE, TinyMedia.unknown(), "photo.png", POLICY))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("not a supported");
    }

    @Test
    void saysSoWhenWhenTheBytesAreEmpty() {
        assertThatThrownBy(() -> Media.of(MediaKind.IMAGE, new byte[0], "x.png", POLICY))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("is empty");
    }

    @ParameterizedTest
    @MethodSource("tooShortToTell")
    void refusesBytesTooShortToHoldAHeader(byte[] bytes) {
        assertThatThrownBy(() -> Media.of(MediaKind.IMAGE, bytes, "x.png", POLICY))
                .isInstanceOf(InvalidRequestException.class);
    }

    static Stream<byte[]> tooShortToTell() {
        return Stream.of(new byte[] {(byte) 0x89, 'P'}, new byte[] {1});
    }

    @Test
    void refusesMoreBytesThanThePolicyAllows() {
        MediaPolicy small = MediaPolicy.builder().maxBytesPerItem(32).build();

        assertThatThrownBy(() -> Media.of(MediaKind.IMAGE, TinyMedia.png(), "a.png", small))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("64")
                .hasMessageContaining("32");
    }

    @Test
    void acceptsExactlyTheLimit() {
        MediaPolicy exact = MediaPolicy.builder().maxBytesPerItem(64).build();

        assertThat(Media.of(MediaKind.IMAGE, TinyMedia.png(), "a.png", exact).size()).isEqualTo(64);
    }

    @Test
    void refusesATypeTheCallersPolicyDoesNotAllow() {
        MediaPolicy pngOnly = MediaPolicy.builder().allow(MediaKind.IMAGE, "image/png").build();

        assertThat(Media.of(MediaKind.IMAGE, TinyMedia.png(), "a.png", pngOnly)).isNotNull();
        assertThatThrownBy(() -> Media.of(MediaKind.IMAGE, TinyMedia.jpeg(), "a.jpg", pngOnly))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("image/jpeg")
                .hasMessageContaining("not allowed");
    }

    @Test
    void aKindWithNoAllowListIsRefusedWhole() {
        MediaPolicy noVideo = MediaPolicy.builder().refuse(MediaKind.VIDEO).build();

        assertThatThrownBy(() -> Media.of(MediaKind.VIDEO, TinyMedia.mp4(), "a.mp4", noVideo))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("VIDEO");
    }

    @Test
    void keepsItsOwnCopyOfTheBytes() {
        byte[] original = TinyMedia.png();
        Media media = Media.of(MediaKind.IMAGE, original, "a.png", POLICY);
        String hash = media.sha256();

        original[0] = 0; // the caller reuses its buffer
        media.bytes()[1] = 0; // or scribbles on what it was given back

        assertThat(media.bytes()).isEqualTo(TinyMedia.png());
        assertThat(media.sha256()).isEqualTo(hash);
    }

    @Test
    void hashesTheBytesWithSha256() throws Exception {
        byte[] bytes = TinyMedia.png();
        String expected =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));

        assertThat(Media.of(MediaKind.IMAGE, bytes, "a.png", POLICY).sha256()).isEqualTo(expected);
    }

    @Test
    void encodesItsContentOnlyWhenAskedFor() {
        Media media = Media.of(MediaKind.IMAGE, TinyMedia.png(), "a.png", POLICY);

        assertThat(media.base64()).isEqualTo(TinyMedia.base64(TinyMedia.png()));
    }

    @Test
    void printsKindTypeSizeAndAShortHashAndNothingElse() {
        Media media = Media.of(MediaKind.IMAGE, TinyMedia.png(), "secret-name.png", POLICY);

        assertThat(media.toString())
                .matches("media:image/png:64B:[0-9a-f]{8}")
                .doesNotContain("secret-name")
                .doesNotContain(TinyMedia.base64(TinyMedia.png()));
    }

    @Test
    void isNotSerializableSoItCannotBeWrittenOutByAccident() {
        assertThat(Serializable.class.isAssignableFrom(Media.class)).isFalse();
    }

    @Test
    void serialisesAsItsDescriptorAndNeverItsBytes() throws Exception {
        Media media = Media.of(MediaKind.AUDIO, TinyMedia.wav(), "call.wav", POLICY);

        String json = JSON.writeValueAsString(media);
        JsonNode node = JSON.readTree(json);

        assertThat(node.get("kind").asText()).isEqualTo("AUDIO");
        assertThat(node.get("mediaType").asText()).isEqualTo("audio/wav");
        assertThat(node.get("bytes").asLong()).isEqualTo(64);
        assertThat(node.get("sha256").asText()).isEqualTo(media.sha256());
        assertThat(node.get("name").asText()).isEqualTo("call.wav");
        assertThat(node.fieldNames()).toIterable().hasSize(5);
        assertThat(json).doesNotContain(TinyMedia.base64(TinyMedia.wav()));
    }

    @ParameterizedTest
    @MethodSource("namesAndWhatTheyBecome")
    void keepsOnlyTheFileNameAndOnlyPrintableCharacters(String given, String kept) {
        assertThat(Media.of(MediaKind.IMAGE, TinyMedia.png(), given, POLICY).name())
                .isEqualTo(kept);
    }

    static Stream<Arguments> namesAndWhatTheyBecome() {
        return Stream.of(
                Arguments.of("receipt.png", "receipt.png"),
                Arguments.of("/home/user/.ssh/receipt.png", "receipt.png"),
                Arguments.of("..\\..\\secrets\\receipt.png", "receipt.png"),
                Arguments.of("re\nceipt\u0000.png", "receipt.png"),
                Arguments.of(null, "media"),
                Arguments.of("   ", "media"),
                Arguments.of("a".repeat(500), "a".repeat(100)));
    }

    @Test
    void twoMediaWithTheSameContentAreEqual() {
        Media a = Media.of(MediaKind.IMAGE, TinyMedia.png(), "one.png", POLICY);
        Media b = Media.of(MediaKind.IMAGE, TinyMedia.png(), "two.png", POLICY);
        Media c = Media.of(MediaKind.IMAGE, TinyMedia.jpeg(), "one.png", POLICY);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b).isNotEqualTo(c);
    }

    @Test
    void readsAFileAndNamesItByItsFileNameAlone(@TempDir Path dir) throws Exception {
        Path file = Files.write(dir.resolve("receipt.png"), TinyMedia.png());

        Media media = Media.image(file, POLICY);

        assertThat(media.name()).isEqualTo("receipt.png");
        assertThat(media.bytes()).isEqualTo(TinyMedia.png());
        assertThat(media.toString()).doesNotContain(dir.toString());
    }

    @Test
    void theConvenienceFactoriesFixTheKind(@TempDir Path dir) throws Exception {
        assertThat(Media.audio(Files.write(dir.resolve("a.wav"), TinyMedia.wav()), POLICY).kind())
                .isEqualTo(MediaKind.AUDIO);
        assertThat(Media.video(Files.write(dir.resolve("a.mp4"), TinyMedia.mp4()), POLICY).kind())
                .isEqualTo(MediaKind.VIDEO);
        assertThat(
                        Media.document(Files.write(dir.resolve("a.pdf"), TinyMedia.pdf()), POLICY)
                                .kind())
                .isEqualTo(MediaKind.DOCUMENT);
    }

    @Test
    void takesAFileOfExactlyTheLimit(@TempDir Path dir) throws Exception {
        byte[] png = TinyMedia.png();
        Path file = Files.write(dir.resolve("exact.png"), png);
        MediaPolicy exact = MediaPolicy.builder().maxBytesPerItem(png.length).build();

        assertThat(Media.image(file, exact).size()).isEqualTo(png.length);
        assertThatThrownBy(
                        () ->
                                Media.image(
                                        file,
                                        MediaPolicy.builder()
                                                .maxBytesPerItem(png.length - 1)
                                                .build()))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void refusesAFileBiggerThanTheLimitWithoutKeepingIt(@TempDir Path dir) throws Exception {
        Path file = Files.write(dir.resolve("big.png"), TinyMedia.png());
        MediaPolicy small = MediaPolicy.builder().maxBytesPerItem(10).build();

        assertThatThrownBy(() -> Media.image(file, small))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("10")
                .hasMessageContaining("big.png");
    }

    @Test
    void refusesWhatIsNotAFile(@TempDir Path dir) {
        assertThatThrownBy(() -> Media.image(dir, POLICY))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("regular file");
        assertThatThrownBy(() -> Media.image(dir.resolve("missing.png"), POLICY))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("missing.png");
    }

    @Test
    void twoFilesWithTheSameBytesAreEqualWhateverTheyAreCalled() {
        Media first = Media.of(MediaKind.IMAGE, TinyMedia.png(), "one.png", POLICY);
        Media second = Media.of(MediaKind.IMAGE, TinyMedia.png(), "two.png", POLICY);
        byte[] other = TinyMedia.png();
        other[40] = 7;
        Media different = Media.of(MediaKind.IMAGE, other, "one.png", POLICY);

        assertThat(first).isEqualTo(first).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(first).isNotEqualTo(different).isNotEqualTo("one.png").isNotEqualTo(null);
        assertThat(first.hashCode()).isNotEqualTo(different.hashCode()).isNotZero();
    }
}
