package io.github.llm4j.model;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * The smallest byte strings the library will accept as each kind of media, and deliberately wrong
 * ones. Only the leading bytes are real, because the library decides the type from them and leaves
 * decoding to the provider; nothing here is a viewable file, and no binary file is committed.
 */
final class TinyMedia {

    /** Long enough for every format check that looks past the first few bytes. */
    private static final int LENGTH = 64;

    private TinyMedia() {}

    static byte[] png() {
        return padded(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
    }

    static byte[] jpeg() {
        return padded(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0});
    }

    static byte[] gif() {
        return padded("GIF89a".getBytes(StandardCharsets.US_ASCII));
    }

    static byte[] webp() {
        return riff("WEBP");
    }

    static byte[] pdf() {
        return padded("%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII));
    }

    static byte[] wav() {
        return riff("WAVE");
    }

    static byte[] mp3() {
        return padded(new byte[] {(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x00});
    }

    static byte[] mp3WithId3() {
        return padded("ID3\u0004".getBytes(StandardCharsets.ISO_8859_1));
    }

    static byte[] mp4() {
        return ftyp("isom");
    }

    static byte[] m4a() {
        return ftyp("M4A ");
    }

    static byte[] webm() {
        return padded(new byte[] {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3});
    }

    /** Text, which the library never takes for an image although a browser would draw it. */
    static byte[] svg() {
        return padded(
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>x()</script></svg>"
                        .getBytes(StandardCharsets.UTF_8));
    }

    static byte[] unknown() {
        return padded("just some text, not media".getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * What a payload of {@code bytes} looks like once base64-encoded: the leak assertion's needle.
     */
    static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] riff(String form) {
        byte[] out = new byte[LENGTH];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, out, 0, 4);
        System.arraycopy(form.getBytes(StandardCharsets.US_ASCII), 0, out, 8, 4);
        return out;
    }

    private static byte[] ftyp(String brand) {
        byte[] out = new byte[LENGTH];
        System.arraycopy("ftyp".getBytes(StandardCharsets.US_ASCII), 0, out, 4, 4);
        System.arraycopy(brand.getBytes(StandardCharsets.US_ASCII), 0, out, 8, 4);
        return out;
    }

    private static byte[] padded(byte[] header) {
        return Arrays.copyOf(header, Math.max(LENGTH, header.length));
    }
}
