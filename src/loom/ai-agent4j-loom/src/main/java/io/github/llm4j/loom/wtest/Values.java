package io.github.llm4j.loom.wtest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Small helpers for the values a run produces: JSON text read back, cutting, hashing. */
final class Values {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Values() {}

    static Optional<Map<String, Object>> parseObject(String text) {
        String t = text == null ? "" : text.strip();
        if (!t.startsWith("{")) return Optional.empty();
        try {
            return Optional.of(JSON.readValue(t, new TypeReference<Map<String, Object>>() {}));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    static Optional<List<Object>> parseList(String text) {
        String t = text == null ? "" : text.strip();
        if (!t.startsWith("[")) return Optional.empty();
        try {
            return Optional.of(JSON.readValue(t, new TypeReference<List<Object>>() {}));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Text shortened to {@code max} characters; long text says how long it was and a hash. */
    static String cut(String s, int max, boolean quoted) {
        if (s.length() <= max) return s;
        String head = s.substring(0, max);
        return head + "… (" + s.length() + " characters, sha256 " + hash(s) + ")";
    }

    static String hash(String s) {
        try {
            byte[] d =
                    MessageDigest.getInstance("SHA-256")
                            .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 12);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A value read by a dotted path through maps ({@code intent.kind}); null when absent. */
    static Object path(Object root, String path) {
        Object cur = root;
        for (String part : path.split("\\.")) {
            if (cur instanceof String s) cur = parseObject(s).map(m -> (Object) m).orElse(cur);
            if (!(cur instanceof Map<?, ?> m)) return null;
            cur = m.get(part);
            if (cur == null) return null;
        }
        return cur;
    }
}
