package io.github.llm4j.loom.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** V3.6, V3.7: the JSON follows its schema, uses absolute paths and leaves out what is not set. */
class GraphJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final GraphService service = new GraphService();

    static JsonSchema schema() throws Exception {
        try (InputStream in = GraphJsonTest.class.getResourceAsStream("/io/github/llm4j/loom/graph/graph-result.schema.json")) {
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "samples/content_factory/main.loom",
        "samples/boardroom/main.loom",
        "samples/digest/digest.loom",
        "src/test/resources/imports/parent.loom",
        "src/test/resources/graph/all_statements.loom",
        "src/test/resources/graph/handlers.loom",
        "src/test/resources/graph/unresolved_call.loom",
        "src/test/resources/graph/diamond/a.loom",
        "src/test/resources/graph/missing_import.loom",
        "src/test/resources/imports/circular_a.loom"
    })
    void theJsonValidatesAgainstTheSchema(String script) throws Exception {
        JsonNode json = MAPPER.readTree(GraphJson.write(service.graph(Path.of(script))));

        assertThat(schema().validate(json)).isEmpty();
        assertThat(json.get("version").asInt()).isEqualTo(1);
    }

    @Test
    void theSchemaRejectsAnUnknownKeyAndAWrongVersion() throws Exception {
        JsonNode json = MAPPER.readTree(GraphJson.write(service.graph(Path.of("src/test/resources/imports/parent.loom"))));

        ((com.fasterxml.jackson.databind.node.ObjectNode) json).put("surprise", 1);
        assertThat(schema().validate(json)).isNotEmpty();
        ((com.fasterxml.jackson.databind.node.ObjectNode) json).remove("surprise");
        ((com.fasterxml.jackson.databind.node.ObjectNode) json).put("version", 2);
        assertThat(schema().validate(json)).isNotEmpty();
    }

    @Test
    void everyPathIsAbsoluteAndNormalised() throws Exception {
        JsonNode json = MAPPER.readTree(GraphJson.write(service.graph(Path.of("src/test/resources/graph/diamond/a.loom"))));

        List<String> paths = new java.util.ArrayList<>();
        collect(json, paths);
        assertThat(paths).isNotEmpty().allSatisfy(p -> {
            assertThat(Path.of(p).isAbsolute()).isTrue();
            assertThat(Path.of(p).normalize()).isEqualTo(Path.of(p));
        });
    }

    private static void collect(JsonNode node, List<String> paths) {
        if (node.isObject()) {
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                JsonNode child = node.get(name);
                if ((name.equals("file") || name.equals("path") || name.equals("entry")) && child.isTextual()) {
                    paths.add(child.asText());
                }
                collect(child, paths);
            }
        } else if (node.isArray()) {
            node.forEach(c -> collect(c, paths));
            if (node.size() > 0 && node.get(0).isTextual() && node.toString().contains("/")) {
                node.forEach(c -> paths.add(c.asText()));
            }
        }
    }

    @Test
    void noValueIsNullAndAnUnsetKeyIsLeftOut() throws Exception {
        JsonNode json = MAPPER.readTree(GraphJson.write(service.graph(Path.of("src/test/resources/graph/handlers.loom"))));

        assertThat(json.toString()).doesNotContain("null");
        JsonNode start = json.get("workflows").get(0).get("nodes").get(0);
        assertThat(start.has("source")).isFalse();
        assertThat(start.has("attrs")).isFalse();
        assertThat(start.has("agent")).isFalse();
    }

    @Test
    void theSameScriptAlwaysGivesTheSameBytes() {
        String first = GraphJson.write(service.graph(Path.of("samples/boardroom/main.loom")));
        String second = GraphJson.write(service.graph(Path.of("samples/boardroom/main.loom")));

        assertThat(second).isEqualTo(first);
        assertThat(first).doesNotContain("\r");
    }

    @Test
    void anUnresolvedCallIsFlaggedAndKeepsTheCalleeName() throws Exception {
        JsonNode json = MAPPER.readTree(GraphJson.write(service.graph(Path.of("src/test/resources/graph/unresolved_call.loom"))));

        JsonNode call = json.get("workflows").get(0).get("nodes").get(1);
        assertThat(call.get("unresolved").asBoolean()).isTrue();
        assertThat(call.get("call").get("workflow").asText()).isEqualTo("Nowhere");
        assertThat(call.get("call").has("file")).isFalse();
    }
}
