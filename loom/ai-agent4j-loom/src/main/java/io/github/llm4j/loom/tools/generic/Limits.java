package io.github.llm4j.loom.tools.generic;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Bounds on what a tool reads and returns, so a large body or a chatty process can't fill memory. */
public final class Limits {

    private Limits() {}

    /** Bytes read, and whether more were left unread. */
    public record Capped(byte[] bytes, boolean truncated) {
        public String text() {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /** Reads at most {@code maxBytes}; one more byte is read to find out whether the stream had more. */
    public static Capped readCapped(InputStream in, long maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(maxBytes, 8192));
        byte[] buf = new byte[8192];
        long total = 0;
        boolean more = false;
        int n;
        while ((n = in.read(buf)) != -1) {
            long room = maxBytes - total;
            if (n > room) {
                out.write(buf, 0, (int) room);
                more = true;
                break;
            }
            out.write(buf, 0, n);
            total += n;
            if (total == maxBytes) {
                more = in.read() != -1;
                break;
            }
        }
        return new Capped(out.toByteArray(), more);
    }

    /** The text cut to {@code maxBytes} (UTF-8) at a character boundary, with a marker saying how much went. */
    public static String cut(String text, long maxBytes) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) return text;
        int keep = (int) maxBytes;
        while (keep > 0 && (bytes[keep] & 0xC0) == 0x80) keep--; // don't split a multi-byte character
        return new String(bytes, 0, keep, StandardCharsets.UTF_8) + marker(keep, bytes.length);
    }

    /** The marker for a read that stopped at {@code kept} bytes without knowing the full size. */
    public static String markerUnknownTotal(long kept) {
        return "\n… [cut: first " + kept + " bytes shown, more follows]";
    }

    private static String marker(long kept, long total) {
        return "\n… [cut: " + kept + " of " + total + " bytes shown]";
    }

    /** A single line of at most {@code max} characters, for error excerpts. */
    public static String excerpt(String text, int max) {
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }
}
