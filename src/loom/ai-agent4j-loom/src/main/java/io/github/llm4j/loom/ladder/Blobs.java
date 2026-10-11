package io.github.llm4j.loom.ladder;

import io.github.llm4j.loom.autonomy.Rec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Artifacts too large to keep in the record of a case. The text is written once, under the hash of its content, as a record of its own; the case's record
 * says which (and how big it is, and how it starts). Two cases with the same text share one copy, and retention removes the text from this one place.
 */
public final class Blobs {

    /** Text longer than this (in characters) is kept apart from the record that refers to it. */
    public static final int LIMIT = 16 * 1024;

    private static final int HEAD = 160;

    private Blobs() { }

    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * What a record keeps for an artifact called {@code key} ({@code value}, {@code final}): the text itself when it is small, else a reference to it, its size
     * and its first characters. The text of a large one is written to the ledger here, once.
     */
    public static Map<String, Object> keep(Consumer<Rec> write, String decision, String key, String text, Instant at) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (text == null) return out;
        if (text.length() <= LIMIT) {
            out.put(key, text);
            return out;
        }
        String ref = hash(text);
        write.accept(new Rec(decision + "#blob#" + ref, decision, Rec.BLOB, at, "", 1, Rec.map("text", text, "size", text.length())));
        out.put(key + "Ref", ref);
        out.put(key + "Size", text.length());
        out.put(key + "Head", text.substring(0, HEAD));
        return out;
    }

    /** The text of every blob the records hold, by reference; a purged one has no entry. */
    static Map<String, String> index(List<Rec> records) {
        Map<String, String> out = new HashMap<>();
        for (Rec r : records) {
            if (Rec.BLOB.equals(r.kind()) && r.str("text") != null) out.put(r.id().substring(r.id().lastIndexOf('#') + 1), r.str("text"));
        }
        return out;
    }

    /** The artifact {@code key} of a record: its text, or the text its reference names, or null when it was never there or has expired. */
    static String read(Rec r, String key, Map<String, String> blobs) {
        if (r == null) return null;
        String inline = r.str(key);
        if (inline != null) return inline;
        String ref = r.str(key + "Ref");
        return ref == null ? null : blobs.get(ref);
    }
}
