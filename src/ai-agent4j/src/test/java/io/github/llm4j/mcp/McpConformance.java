package io.github.llm4j.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Checks a message against the official JSON Schema of the MCP revision it speaks (phase 36, D15
 * rule 2): the envelope, and then the parameters or result the schema defines for its method. The
 * schemas are vendored under {@code src/test/resources/mcp/schema}, unchanged, with their source
 * and hashes in {@code METADATA.json}.
 */
final class McpConformance {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The request type the schema defines for each method this client sends. */
    private static final Map<String, String> REQUESTS =
            Map.of(
                    "initialize", "InitializeRequest",
                    "tools/list", "ListToolsRequest",
                    "tools/call", "CallToolRequest",
                    "notifications/initialized", "InitializedNotification");

    /** The result type the schema defines for each request method. */
    private static final Map<String, String> RESULTS =
            Map.of(
                    "initialize", "InitializeResult",
                    "tools/list", "ListToolsResult",
                    "tools/call", "CallToolResult");

    private final ObjectNode root;
    private final JsonSchemaFactory factory =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    McpConformance(String revision) {
        try (InputStream in =
                McpConformance.class.getResourceAsStream("/mcp/schema/" + revision + ".json")) {
            if (in == null) throw new IllegalStateException("no schema vendored for " + revision);
            this.root = (ObjectNode) JSON.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** What is wrong with {@code line} (one JSON-RPC message), empty when it conforms. */
    List<String> problems(String line, String methodOfRequestItAnswers) {
        List<String> out = new ArrayList<>();
        JsonNode message;
        try {
            message = JSON.readTree(line);
        } catch (IOException e) {
            return List.of("not JSON: " + line);
        }
        check(message, "JSONRPCMessage", out, "envelope");
        if (message.has("method")) {
            String type = REQUESTS.get(message.get("method").asText());
            if (type != null) check(message, type, out, message.get("method").asText());
        } else if (message.has("result") && methodOfRequestItAnswers != null) {
            String type = RESULTS.get(methodOfRequestItAnswers);
            if (type != null) check(message.get("result"), type, out, "result of " + type);
        }
        return out;
    }

    private void check(JsonNode node, String type, List<String> out, String what) {
        ObjectNode wrapper = root.deepCopy();
        wrapper.put("$ref", "#/$defs/" + type);
        JsonSchema schema = factory.getSchema(wrapper);
        schema.validate(node)
                .forEach(m -> out.add(what + " against " + type + ": " + m.getMessage()));
    }
}
