package io.github.llm4j.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The type of a file comes from its first bytes (docs/specs/31-multimodal-input R3): every format
 * the library takes is recognised, and near misses are not.
 */
class MediaSnifferTest {

    private static final int HEADER = 12;

    private static byte[] padded(byte... head) {
        return Arrays.copyOf(head, 64);
    }

    private static byte[] with(String tag, int at) {
        byte[] out = new byte[64];
        System.arraycopy(tag.getBytes(StandardCharsets.US_ASCII), 0, out, at, tag.length());
        return out;
    }

    private static Optional<MediaSniffer.Sniffed> sniff(byte[] bytes) {
        return MediaSniffer.sniff(bytes);
    }

    @Test
    void everyFormatIsRecognisedByItsLeadingBytes() {
        assertThat(sniff(TinyMedia.png()))
                .contains(new MediaSniffer.Sniffed(MediaKind.IMAGE, "image/png"));
        assertThat(sniff(TinyMedia.jpeg()))
                .contains(new MediaSniffer.Sniffed(MediaKind.IMAGE, "image/jpeg"));
        assertThat(sniff(TinyMedia.gif()))
                .contains(new MediaSniffer.Sniffed(MediaKind.IMAGE, "image/gif"));
        assertThat(sniff(TinyMedia.webp()))
                .contains(new MediaSniffer.Sniffed(MediaKind.IMAGE, "image/webp"));
        assertThat(sniff(TinyMedia.pdf()))
                .contains(new MediaSniffer.Sniffed(MediaKind.DOCUMENT, "application/pdf"));
        assertThat(sniff(TinyMedia.wav()))
                .contains(new MediaSniffer.Sniffed(MediaKind.AUDIO, "audio/wav"));
        assertThat(sniff(TinyMedia.mp3WithId3()))
                .contains(new MediaSniffer.Sniffed(MediaKind.AUDIO, "audio/mpeg"));
        assertThat(sniff(TinyMedia.webm()))
                .contains(new MediaSniffer.Sniffed(MediaKind.VIDEO, "video/webm"));
        assertThat(sniff(with("fLaC", 0)))
                .contains(new MediaSniffer.Sniffed(MediaKind.AUDIO, "audio/flac"));
        assertThat(sniff(with("OggS", 0)))
                .contains(new MediaSniffer.Sniffed(MediaKind.AUDIO, "audio/ogg"));
    }

    @Test
    void theBrandOfAnIsoFileSaysWhetherItIsSoundOrPictures() {
        byte[] m4a = with("ftyp", 4);
        System.arraycopy("M4A ".getBytes(StandardCharsets.US_ASCII), 0, m4a, 8, 4);
        byte[] quicktime = with("ftyp", 4);
        System.arraycopy("qt  ".getBytes(StandardCharsets.US_ASCII), 0, quicktime, 8, 4);

        assertThat(sniff(m4a)).contains(new MediaSniffer.Sniffed(MediaKind.AUDIO, "audio/mp4"));
        assertThat(sniff(quicktime))
                .contains(new MediaSniffer.Sniffed(MediaKind.VIDEO, "video/quicktime"));
        assertThat(sniff(TinyMedia.mp4()))
                .contains(new MediaSniffer.Sniffed(MediaKind.VIDEO, "video/mp4"));
    }

    @Test
    void aFileOfExactlyTheHeaderLengthIsStillRecognisedAndOneByteLessIsNot() {
        byte[] png = Arrays.copyOf(TinyMedia.png(), HEADER);
        byte[] webp = Arrays.copyOf(TinyMedia.webp(), HEADER);

        assertThat(sniff(png)).isPresent();
        assertThat(sniff(webp)).as("a tag that ends on the last byte").isPresent();
        assertThat(sniff(Arrays.copyOf(png, HEADER - 1))).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "0xFF, 0xFB, true", // MPEG-1 layer III
        "0xFF, 0xF3, true", // MPEG-2 layer III
        "0xFF, 0xE3, true", // MPEG-2.5 layer III
        "0xFF, 0xEB, false", // the reserved version
        "0xFF, 0xF9, false", // the reserved layer
        "0xFF, 0xDB, false", // too few sync bits
        "0xFE, 0xFB, false" // the first byte is not all ones
    })
    void anMpegAudioFrameNeedsEveryOneOfItsHeaderFieldsToBeValid(
            int first, int second, boolean audio) {
        byte[] bytes = padded((byte) first, (byte) second, (byte) 0x90, (byte) 0x00);

        assertThat(sniff(bytes).isPresent()).isEqualTo(audio);
    }

    @Test
    void textAndEmptyBytesAreNotMedia() {
        assertThat(sniff(TinyMedia.unknown())).isEmpty();
        assertThat(sniff(new byte[0])).isEmpty();
    }
}
