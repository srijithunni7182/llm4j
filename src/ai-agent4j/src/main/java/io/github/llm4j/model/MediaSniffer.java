package io.github.llm4j.model;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Decides what bytes are from their first bytes, never from a name or a label the caller supplies.
 *
 * <p>Only the leading bytes are read, so a file that begins like a picture and is something else
 * further on still passes; decoding is the provider's job. What this closes is the cheap confusion:
 * a script, a page or an archive handed over as a picture. The list is closed on purpose, and SVG
 * is not on it because it is text that can carry script.
 */
final class MediaSniffer {

    /** A sniffed format: the kind it belongs to and its media type. */
    record Sniffed(MediaKind kind, String mediaType) {}

    /** Every media type the library can recognise, by kind. */
    static final Map<MediaKind, Set<String>> SUPPORTED = supported();

    /** The most bytes any check below looks at. */
    private static final int HEADER = 12;

    private static final int FTYP_BRAND_AT = 8;
    private static final int FTYP_TAG_AT = 4;
    private static final int RIFF_FORM_AT = 8;
    private static final int MPEG_SYNC_MASK = 0xE0;
    private static final int MPEG_VERSION_RESERVED = 1;

    private MediaSniffer() {}

    static Optional<Sniffed> sniff(byte[] bytes) {
        if (bytes.length < HEADER) return Optional.empty();
        if (startsWith(bytes, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A))
            return image("image/png");
        if (startsWith(bytes, 0xFF, 0xD8, 0xFF)) return image("image/jpeg");
        if (ascii(bytes, 0, "GIF87a") || ascii(bytes, 0, "GIF89a")) return image("image/gif");
        if (ascii(bytes, 0, "RIFF") && ascii(bytes, RIFF_FORM_AT, "WEBP"))
            return image("image/webp");
        if (ascii(bytes, 0, "%PDF-")) return of(MediaKind.DOCUMENT, "application/pdf");
        if (ascii(bytes, 0, "RIFF") && ascii(bytes, RIFF_FORM_AT, "WAVE")) {
            return of(MediaKind.AUDIO, "audio/wav");
        }
        if (ascii(bytes, 0, "ID3") || isMpegAudioFrame(bytes))
            return of(MediaKind.AUDIO, "audio/mpeg");
        if (ascii(bytes, 0, "fLaC")) return of(MediaKind.AUDIO, "audio/flac");
        if (ascii(bytes, 0, "OggS")) return of(MediaKind.AUDIO, "audio/ogg");
        if (ascii(bytes, FTYP_TAG_AT, "ftyp")) return isoBaseMedia(bytes);
        if (startsWith(bytes, 0x1A, 0x45, 0xDF, 0xA3)) return of(MediaKind.VIDEO, "video/webm");
        return Optional.empty();
    }

    /** MP4 and its relatives share a container; the brand says whether it is sound or pictures. */
    private static Optional<Sniffed> isoBaseMedia(byte[] bytes) {
        if (ascii(bytes, FTYP_BRAND_AT, "M4A ")) return of(MediaKind.AUDIO, "audio/mp4");
        if (ascii(bytes, FTYP_BRAND_AT, "qt  ")) return of(MediaKind.VIDEO, "video/quicktime");
        return of(MediaKind.VIDEO, "video/mp4");
    }

    /** An MPEG audio frame header: eleven set bits, then a version and a layer that exist. */
    private static boolean isMpegAudioFrame(byte[] bytes) {
        int first = bytes[0] & 0xFF;
        int second = bytes[1] & 0xFF;
        if (first != 0xFF || (second & MPEG_SYNC_MASK) != MPEG_SYNC_MASK) return false;
        int version = (second >> 3) & 0b11;
        int layer = (second >> 1) & 0b11;
        return version != MPEG_VERSION_RESERVED && layer != 0;
    }

    private static Optional<Sniffed> image(String mediaType) {
        return of(MediaKind.IMAGE, mediaType);
    }

    private static Optional<Sniffed> of(MediaKind kind, String mediaType) {
        return Optional.of(new Sniffed(kind, mediaType));
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) return false;
        }
        return true;
    }

    private static boolean ascii(byte[] bytes, int at, String text) {
        byte[] expected = text.getBytes(StandardCharsets.US_ASCII);
        if (at + expected.length > bytes.length) return false;
        for (int i = 0; i < expected.length; i++) {
            if (bytes[at + i] != expected[i]) return false;
        }
        return true;
    }

    private static Map<MediaKind, Set<String>> supported() {
        Map<MediaKind, Set<String>> out = new EnumMap<>(MediaKind.class);
        out.put(MediaKind.IMAGE, Set.of("image/png", "image/jpeg", "image/gif", "image/webp"));
        out.put(MediaKind.DOCUMENT, Set.of("application/pdf"));
        out.put(
                MediaKind.AUDIO,
                Set.of("audio/wav", "audio/mpeg", "audio/flac", "audio/ogg", "audio/mp4"));
        out.put(MediaKind.VIDEO, Set.of("video/mp4", "video/webm", "video/quicktime"));
        return Map.copyOf(out);
    }
}
