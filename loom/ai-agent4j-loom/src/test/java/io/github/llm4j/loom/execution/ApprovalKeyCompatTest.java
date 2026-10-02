package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** V2.11: moving the hashing to CanonicalArgs must not change approval keys, or old paused runs would re-ask. */
class ApprovalKeyCompatTest {

    private static final ObjectMapper CANONICAL = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    /** The algorithm as it was before the move, kept here as the reference. */
    private static String reference(String step, String tool, Map<String, Object> args) throws Exception {
        String json = CANONICAL.writeValueAsString(sorted(args == null ? Map.of() : args));
        byte[] digest = MessageDigest.getInstance("SHA-256").digest((tool + json).getBytes(StandardCharsets.UTF_8));
        return step + "#approve:" + tool + ":" + HexFormat.of().formatHex(digest).substring(0, 12);
    }

    private static Object sorted(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new TreeMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), sorted(v)));
            return out;
        }
        if (value instanceof List<?> l) return l.stream().map(ApprovalKeyCompatTest::sorted).toList();
        return value;
    }

    @Test
    @Tag("V2.11")
    void keysAreTheSameAsTheyWereBeforeTheHashingMoved() throws Exception {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("z", List.of(3, Map.of("b", 1, "a", 2)));
        nested.put("a", "ü \"quoted\"");
        List<Map<String, Object>> cases = List.of(Map.of(), Map.of("q", "x"), Map.of("n", 5, "t", true, "s", "text"), nested);
        for (Map<String, Object> args : cases) {
            assertThat(ApprovalGate.key("step-1", "Slack", args)).isEqualTo(reference("step-1", "Slack", args));
        }
        assertThat(ApprovalGate.key("s", "T", null)).isEqualTo(reference("s", "T", null));
        // Key order never matters.
        assertThat(ApprovalGate.key("s", "T", Map.of("a", 1, "b", 2))).isEqualTo(ApprovalGate.key("s", "T", new LinkedHashMap<>(Map.of("b", 2, "a", 1))));
    }
}
