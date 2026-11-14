package io.github.llm4j.tools;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Reads a stream to its end and keeps the start and the end of it within a cap (D6): a build's
 * errors are at the end, so cutting only the end would lose them. Says how many bytes were cut.
 */
final class HeadAndTail {

    private final int headCap;
    private final int tailCap;
    private final ByteArrayOutputStream head = new ByteArrayOutputStream();
    private final byte[] tail;
    private int tailStart;
    private int tailSize;
    private long total;

    HeadAndTail(long cap) {
        int c = (int) Math.min(cap, Integer.MAX_VALUE - 8);
        this.headCap = c / 2;
        this.tailCap = c - headCap;
        this.tail = new byte[tailCap];
    }

    void add(byte b) {
        total++;
        if (head.size() < headCap) {
            head.write(b);
            return;
        }
        if (tailCap == 0) return;
        int at = (tailStart + tailSize) % tailCap;
        tail[at] = b;
        if (tailSize < tailCap) tailSize++;
        else tailStart = (tailStart + 1) % tailCap;
    }

    long cut() {
        return total - head.size() - tailSize;
    }

    /** The text kept, with a marker where the middle was cut. */
    String text() {
        byte[] end = new byte[tailSize];
        for (int i = 0; i < tailSize; i++) end[i] = tail[(tailStart + i) % tailCap];
        String start = head.toString(StandardCharsets.UTF_8);
        String finish = new String(end, StandardCharsets.UTF_8);
        return cut() == 0 ? start + finish : start + "\n… [" + cut() + " bytes cut] …\n" + finish;
    }
}
