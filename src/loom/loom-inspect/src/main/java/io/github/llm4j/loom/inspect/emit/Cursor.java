package io.github.llm4j.loom.inspect.emit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * How far one destination (Fabric, an OTLP collector) has acknowledged, kept in a small file beside the log. It is moved only after
 * the destination says it has the records, so a crash between sending and recording just sends a batch again.
 */
public final class Cursor {

    private final Path file;
    private long value;

    public Cursor(Path outboxDir, String destination) {
        this.file = outboxDir.resolve(destination + ".cursor");
        long v = 0;
        try {
            if (Files.exists(file)) v = Long.parseLong(Files.readString(file).trim());
        } catch (IOException | NumberFormatException e) {
            v = 0; // an unreadable cursor starts from the beginning: duplicates are dropped by the server, a gap would not be
        }
        this.value = v;
    }

    /** The last acknowledged offset (0 when nothing was). */
    public synchronized long get() {
        return value;
    }

    /** Records that everything up to {@code offset} is acknowledged. Never moves backwards. */
    public synchronized void advance(long offset) {
        if (offset <= value) return;
        try {
            Outbox.writeAtomically(file, Long.toString(offset));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        value = offset;
    }
}
