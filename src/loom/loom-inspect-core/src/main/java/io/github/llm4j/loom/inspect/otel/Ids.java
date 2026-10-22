package io.github.llm4j.loom.inspect.otel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Trace and span ids derived from Loom's own ids, so they are the same after a pause, a sleep or a
 * restart. A run keeps one trace for its whole life, and the process that wakes a step can export
 * its span under the id the first process gave it.
 */
public final class Ids {

    private Ids() {}

    /** 32 lowercase hex characters (16 bytes). */
    public static String traceId(String runId) {
        return hex(digest(runId), 16);
    }

    /**
     * 16 lowercase hex characters (8 bytes), for a step or other element of the run, named by
     * {@code key}.
     */
    public static String spanId(String runId, String key) {
        return hex(digest(runId + "\u0000" + key), 8);
    }

    private static byte[] digest(String text) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes, int length) {
        StringBuilder out = new StringBuilder(length * 2);
        boolean zero = true;
        for (int i = 0; i < length; i++) {
            if (bytes[i] != 0) zero = false;
            out.append(String.format("%02x", bytes[i] & 0xff));
        }
        // an all-zero id is invalid in OpenTelemetry
        return zero ? "1".repeat(length * 2) : out.toString();
    }
}
