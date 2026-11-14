package io.github.llm4j.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The pin of an MCP server: a short hash of what its tool list promises, so a later change to a tool
 * is noticed. It covers each tool's name, input schema and annotations; not descriptions, titles,
 * examples or the order of tools or keys, so rewording documentation raises no alarm. A pin proves the
 * tool list is the one that was reviewed; it says nothing about what the tools do.
 */
public final class McpPin {

    /** The prefix that says which hash a pin is. */
    public static final String PREFIX = "sha256-";

    private static final int HEX_CHARS = 16;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> DESCRIPTIVE =
            Set.of("description", "title", "example", "examples", "$comment");

    private McpPin() {}

    /**
     * The pin of a tool list.
     *
     * @param tools the tools as {@code tools/list} gave them
     */
    public static String of(List<Map<String, Object>> tools) {
        ArrayNode list = JSON.createArrayNode();
        tools.stream()
                .sorted(Comparator.comparing(t -> String.valueOf(t.get("name"))))
                .forEach(
                        tool -> {
                            ObjectNode entry = JSON.createObjectNode();
                            entry.put("name", String.valueOf(tool.get("name")));
                            entry.set("inputSchema", canonical(JSON.valueToTree(tool.get("inputSchema"))));
                            entry.set("annotations", canonical(annotationsOf(tool)));
                            list.add(entry);
                        });
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(JSON.writeValueAsString(list).getBytes(StandardCharsets.UTF_8));
            return PREFIX + HexFormat.of().formatHex(digest).substring(0, HEX_CHARS);
        } catch (NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("a tool list could not be hashed", e);
        }
    }

    /** The annotations without their {@code title}, which only describes. */
    private static JsonNode annotationsOf(Map<String, Object> tool) {
        JsonNode node = JSON.valueToTree(tool.get("annotations"));
        return node;
    }

    private static JsonNode canonical(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return JSON.nullNode();
        if (node.isObject()) {
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
                String name = names.next();
                if (!DESCRIPTIVE.contains(name)) sorted.put(name, canonical(node.get(name)));
            }
            ObjectNode out = JSON.createObjectNode();
            sorted.forEach(out::set);
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JSON.createArrayNode();
            node.forEach(item -> out.add(canonical(item)));
            return out;
        }
        return node;
    }
}
