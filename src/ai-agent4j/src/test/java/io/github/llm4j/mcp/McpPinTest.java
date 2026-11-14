package io.github.llm4j.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Phase 36a, D12 and G88: an MCP pin is stable under cosmetic changes and moves with what a caller
 * can feel.
 */
class McpPinTest {

    private static Map<String, Object> tool(
            String name,
            String description,
            Map<String, Object> properties,
            Map<String, Object> annotations) {
        return annotations == null
                ? Map.of(
                        "name",
                        name,
                        "description",
                        description,
                        "inputSchema",
                        Map.of("type", "object", "properties", properties))
                : Map.of(
                        "name",
                        name,
                        "description",
                        description,
                        "inputSchema",
                        Map.of("type", "object", "properties", properties),
                        "annotations",
                        annotations);
    }

    private static final Map<String, Object> PATH =
            Map.of("path", Map.of("type", "string", "description", "where"));

    @Test
    void aPinHasThePrefixAndAShortHash() {
        assertThat(McpPin.of(List.of(tool("read", "d", PATH, null))))
                .matches("sha256-[0-9a-f]{16}");
    }

    @Test
    void rewordingDescriptionsOrReorderingToolsDoesNotFlipIt() {
        var a = tool("read", "Reads a file", PATH, null);
        var b =
                tool(
                        "write",
                        "Writes",
                        Map.of("path", Map.of("type", "string"), "text", Map.of("type", "string")),
                        null);
        String pin = McpPin.of(List.of(a, b));

        var reworded =
                tool(
                        "read",
                        "A completely different sentence",
                        Map.of("path", Map.of("type", "string", "description", "other words")),
                        null);

        assertThat(McpPin.of(List.of(b, reworded))).isEqualTo(pin);
    }

    @Test
    void aNewParameterFlipsIt() {
        String before = McpPin.of(List.of(tool("read", "d", PATH, null)));
        String after =
                McpPin.of(
                        List.of(
                                tool(
                                        "read",
                                        "d",
                                        Map.of(
                                                "path",
                                                Map.of("type", "string"),
                                                "recursive",
                                                Map.of("type", "boolean")),
                                        null)));

        assertThat(after).isNotEqualTo(before);
    }

    @Test
    void aChangedAnnotationFlipsIt() {
        String before = McpPin.of(List.of(tool("read", "d", PATH, Map.of("readOnlyHint", true))));
        String after = McpPin.of(List.of(tool("read", "d", PATH, Map.of("readOnlyHint", false))));

        assertThat(after).isNotEqualTo(before);
    }

    @Test
    void aRemovedOrRenamedToolFlipsIt() {
        var read = tool("read", "d", PATH, null);
        var other = tool("other", "d", PATH, null);

        assertThat(McpPin.of(List.of(read))).isNotEqualTo(McpPin.of(List.of(read, other)));
        assertThat(McpPin.of(List.of(read)))
                .isNotEqualTo(McpPin.of(List.of(tool("renamed", "d", PATH, null))));
    }

    @Test
    void anAnnotationTitleDoesNotFlipIt() {
        String before = McpPin.of(List.of(tool("read", "d", PATH, Map.of("readOnlyHint", true))));
        String after =
                McpPin.of(
                        List.of(
                                tool(
                                        "read",
                                        "d",
                                        PATH,
                                        Map.of("readOnlyHint", true, "title", "Read"))));

        assertThat(after).isEqualTo(before);
    }
}
