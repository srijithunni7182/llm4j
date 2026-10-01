package io.github.llm4j.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** A tool call's arguments in one canonical form, so the same call always hashes to the same key. */
public final class CanonicalArgs {

    private static final ObjectMapper JSON = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private CanonicalArgs() {}

    /** The first 12 hex characters of SHA-256 over the tool name and the arguments as sorted-key JSON. */
    public static String hash12(String tool, Map<String, Object> args) {
        return sha256Hex(tool + json(args)).substring(0, 12);
    }

    /** SHA-256 of the text, in hex. */
    public static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String json(Map<String, Object> args) {
        try {
            return JSON.writeValueAsString(sorted(args == null ? Map.of() : args));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not canonicalise tool arguments", e);
        }
    }

    private static Object sorted(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new TreeMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), sorted(v)));
            return out;
        }
        if (value instanceof List<?> l) return l.stream().map(CanonicalArgs::sorted).toList();
        return value;
    }
}
