package io.github.llm4j.loom.serve;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * {@code Content-Length} framing, as the Language Server Protocol does it: a header block ending in
 * an empty line, then that many bytes of UTF-8 JSON.
 */
public final class Framing {

    /** The largest message read; a bigger one is refused and the stream is closed. */
    public static final int MAX_BYTES = 16 * 1024 * 1024;

    /** A frame that cannot be read as one. */
    public static final class BadFrame extends IOException {
        public BadFrame(String message) {
            super(message);
        }
    }

    private Framing() {}

    /** The next message body, or null at a clean end of input. */
    public static String read(InputStream in) throws IOException {
        int length = -1;
        boolean any = false;
        while (true) {
            String line = readLine(in);
            if (line == null) {
                if (any) throw new EOFException("the input ended inside a header");
                return null;
            }
            any = true;
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                try {
                    length = Integer.parseInt(line.substring(colon + 1).trim());
                } catch (NumberFormatException e) {
                    throw new BadFrame("Content-Length is not a number: " + line);
                }
            }
        }
        if (length < 0) throw new BadFrame("a message without Content-Length");
        if (length > MAX_BYTES)
            throw new BadFrame("a message of " + length + " bytes; the limit is " + MAX_BYTES);
        byte[] body = in.readNBytes(length);
        if (body.length < length) throw new EOFException("the input ended inside a message");
        return new String(body, StandardCharsets.UTF_8);
    }

    public static void write(OutputStream out, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        out.write(
                ("Content-Length: " + bytes.length + "\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int c;
        boolean seen = false;
        while ((c = in.read()) != -1) {
            seen = true;
            if (c == '\n') break;
            if (c != '\r') line.write(c);
            if (line.size() > 8192) throw new BadFrame("a header line longer than 8192 bytes");
        }
        if (!seen) return null;
        return line.toString(StandardCharsets.US_ASCII);
    }
}
