package io.github.llm4j.live;

import io.github.llm4j.model.Media;
import io.github.llm4j.model.MediaKind;
import io.github.llm4j.model.MediaPolicy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Real, decodable media for the live tests, built in memory so no binary file is committed: a solid
 * red picture, a one-page PDF that says {@code TOTAL 4217}, a two-second 440 Hz tone as WAV. A
 * video cannot be built in plain Java; the live test takes one from {@code LLM4J_LIVE_MP4} and
 * skips without it.
 */
final class LiveMedia {

    static final MediaPolicy POLICY = MediaPolicy.defaults();
    static final String TOTAL = "4217";

    private LiveMedia() {}

    static Media redPicture() {
        return Media.of(MediaKind.IMAGE, png(32, 32, 255, 0, 0), "red.png", POLICY);
    }

    static Media totalPdf() {
        return Media.of(MediaKind.DOCUMENT, pdf("TOTAL " + TOTAL), "total.pdf", POLICY);
    }

    static Media tone() {
        return Media.of(MediaKind.AUDIO, wav(440, 2), "tone.wav", POLICY);
    }

    /** The clip named by {@code LLM4J_LIVE_MP4}, or null. */
    static Media clip() throws IOException {
        String path = System.getenv("LLM4J_LIVE_MP4");
        return path == null || path.isBlank() ? null : Media.video(Path.of(path), POLICY);
    }

    static byte[] png(int width, int height, int red, int green, int blue) {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (int y = 0; y < height; y++) {
            raw.write(0); // no filter
            for (int x = 0; x < width; x++) {
                raw.write(red);
                raw.write(green);
                raw.write(blue);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        ByteBuffer header = ByteBuffer.allocate(13);
        header.putInt(width)
                .putInt(height)
                .put((byte) 8)
                .put((byte) 2)
                .put((byte) 0)
                .put((byte) 0)
                .put((byte) 0);
        chunk(out, "IHDR", header.array());
        chunk(out, "IDAT", deflate(raw.toByteArray()));
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(name);
        out.writeBytes(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater();
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer));
        deflater.end();
        return out.toByteArray();
    }

    static byte[] pdf(String text) {
        String stream = "BT /F1 24 Tf 40 100 Td (" + text + ") Tj ET";
        String[] objects = {
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Contents 4 0 R"
                    + " /Resources << /Font << /F1 5 0 R >> >> >>",
            "<< /Length " + stream.length() + " >>\nstream\n" + stream + "\nendstream",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
        };
        StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
        int[] offsets = new int[objects.length];
        for (int i = 0; i < objects.length; i++) {
            offsets[i] = pdf.length();
            pdf.append(i + 1).append(" 0 obj\n").append(objects[i]).append("\nendobj\n");
        }
        int xref = pdf.length();
        pdf.append("xref\n0 ").append(objects.length + 1).append("\n0000000000 65535 f \n");
        for (int offset : offsets) pdf.append(String.format("%010d 00000 n \n", offset));
        pdf.append("trailer\n<< /Size ").append(objects.length + 1).append(" /Root 1 0 R >>\n");
        pdf.append("startxref\n").append(xref).append("\n%%EOF\n");
        return pdf.toString().getBytes(StandardCharsets.US_ASCII);
    }

    static byte[] wav(int hertz, int seconds) {
        int rate = 16_000;
        int samples = rate * seconds;
        ByteBuffer data = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < samples; i++) {
            data.putShort((short) (8000 * Math.sin(2 * Math.PI * hertz * i / rate)));
        }
        ByteBuffer out = ByteBuffer.allocate(44 + data.capacity()).order(ByteOrder.LITTLE_ENDIAN);
        out.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + data.capacity());
        out.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1);
        out.putShort((short) 1)
                .putInt(rate)
                .putInt(rate * 2)
                .putShort((short) 2)
                .putShort((short) 16);
        out.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(data.capacity());
        out.put(data.array());
        return out.array();
    }

    /** Whether a file exists at the path in the environment variable. */
    static boolean hasFile(String variable) {
        String path = System.getenv(variable);
        return path != null && !path.isBlank() && Files.isRegularFile(Path.of(path));
    }
}
