package io.github.llm4j.loom.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Runs the canonical scenarios of the phase 35 tool kinds in the Loom Conformance Test Kit ({@code
 * loom/ctk}) with the real kinds, and holds each trace to the canonical one: the tools used, in
 * order, and the notes the script wrote from their results.
 */
class ToolKindsCtkConformanceTest {

    private static final Path CTK = Path.of("..", "ctk").toAbsolutePath().normalize();
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path dir;

    List<Map<String, String>> run(String scenario) throws Exception {
        JsonNode mock = JSON.readTree(Files.readString(CTK.resolve("mocks/" + scenario + ".json")));
        for (JsonNode f : mock.path("folders")) Files.createDirectories(dir.resolve(f.asText()));
        Path script = Files.copy(CTK.resolve("scripts/" + scenario + ".loom"), dir.resolve(scenario + ".loom"));
        HarnessExecutor executor = new HarnessExecutor(new LoomLoader().load(script.toString()), new ToolRegistry(), name -> null);
        executor.setBaseDir(dir);
        executor.setEnvLookup(k -> null);
        List<TraceEvent> events = new ArrayList<>();
        executor.addTraceListener(events::add);
        executor.initialize();
        Map<String, String> input = new HashMap<>();
        mock.path("input").fields().forEachRemaining(e -> input.put(e.getKey(), e.getValue().asText()));
        executor.executeWorkflow("main", input);
        List<Map<String, String>> actual = new ArrayList<>();
        for (TraceEvent ev : events) {
            Map<String, String> step = new LinkedHashMap<>();
            if (ev.type().equals(TraceEvent.TOOL_CALL) && "use".equals(ev.data().get("by"))) {
                step.put("kind", "use");
                step.put("tool", String.valueOf(ev.data().get("tool")));
            } else if (ev.type().equals(TraceEvent.NOTE)) {
                step.put("kind", "note");
                step.put("text", ev.text().strip());
            } else continue;
            actual.add(step);
        }
        return actual;
    }

    static List<Map<String, String>> canonical(String scenario) throws Exception {
        List<Map<String, String>> expected = new ArrayList<>();
        for (JsonNode s : JSON.readTree(Files.readString(CTK.resolve("traces/" + scenario + ".json"))).get("steps")) {
            Map<String, String> step = new LinkedHashMap<>();
            s.fields().forEachRemaining(f -> step.put(f.getKey(), f.getValue().asText()));
            expected.add(step);
        }
        return expected;
    }

    @ParameterizedTest
    @ValueSource(strings = {"tool_compute", "tool_verify", "tool_scan", "tool_workspace", "tool_sandbox"})
    void theScenarioMatchesItsCanonicalTrace(String scenario) throws Exception {
        assertEquals(canonical(scenario), run(scenario), scenario);
    }
}
