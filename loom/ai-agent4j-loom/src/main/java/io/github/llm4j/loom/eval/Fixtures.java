package io.github.llm4j.loom.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.eval.testing.RecordedSearchTool;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.ToolRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Tools that answer from recorded text instead of the outside world, for an evaluation that is free and repeatable. {@code fixtures.yaml}
 * in the dataset folder maps a tool name to entries ({@code match}: a case-insensitive pattern over the query, {@code snippets}: what it finds);
 * a query that matches nothing finds nothing. In a mock run every tool an agent uses is answered this way, whether or not it has fixtures.
 */
public final class Fixtures {

    private final Map<String, RecordedSearchTool> tools;

    private Fixtures(Map<String, RecordedSearchTool> tools) {
        this.tools = tools;
    }

    public static Fixtures none() {
        return new Fixtures(Map.of());
    }

    /** Reads {@code fixtures.yaml} from the folder; none when there is no such file. Throws {@link IllegalArgumentException} for one that cannot be read. */
    public static Fixtures read(Path dir) {
        Path file = dir.resolve("fixtures.yaml");
        if (!Files.isRegularFile(file)) return none();
        try {
            JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(file.toFile());
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("fixtures.yaml: expected a mapping of tool name to entries, for example Search: [{match: \"flights\", snippets: [\"…\"]}]");
            }
            Map<String, RecordedSearchTool> tools = new LinkedHashMap<>();
            root.fields().forEachRemaining(e -> {
                if (!e.getValue().isArray()) {
                    throw new IllegalArgumentException("fixtures.yaml: " + e.getKey() + " should be a list of entries with match and snippets");
                }
                tools.put(e.getKey(), RecordedSearchTool.fromEntries(e.getKey(), e.getValue()));
            });
            return new Fixtures(tools);
        } catch (IOException e) {
            throw new IllegalArgumentException("fixtures.yaml cannot be read: " + e.getMessage(), e);
        }
    }

    public Set<String> names() {
        return tools.keySet();
    }

    /**
     * Puts recorded tools in place of the script's own: the declarations of the tools replaced are removed from {@code script} (a script's
     * declaration wins over a registered tool otherwise) and a recorded tool is registered under each name. With {@code everyTool}, a tool
     * that has no fixtures answers {@value #MOCK_RESULT}, so nothing reaches the outside world.
     *
     * @return the names that were replaced
     */
    public Set<String> apply(LoomScript script, ToolRegistry registry, boolean everyTool) {
        Map<String, Tool> replacements = new LinkedHashMap<>(tools);
        if (everyTool) {
            script.getTools().forEach(def -> replacements.computeIfAbsent(def.getName(), n -> RecordedSearchTool.fixed(n, java.util.List.of(MOCK_RESULT))));
            script.getAgents().forEach(a -> a.getTools().forEach(n -> replacements.computeIfAbsent(n, k -> RecordedSearchTool.fixed(k, java.util.List.of(MOCK_RESULT)))));
        }
        script.getTools().removeIf(def -> replacements.containsKey(def.getName()));
        replacements.forEach(registry::register);
        return replacements.keySet();
    }

    public static final String MOCK_RESULT = "[mock tool result]";
}
