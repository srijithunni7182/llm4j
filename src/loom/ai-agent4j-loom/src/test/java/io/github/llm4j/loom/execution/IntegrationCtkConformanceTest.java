package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.ast.LoomScript;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Runs the canonical scenarios of phase 36 in the Loom Conformance Test Kit ({@code loom/ctk}): a
 * typed command keeps a hostile value as one argument, a rejected credential is told apart by
 * {@code on_failure}, and every shape of an integration declaration loads and passes the checks.
 */
class IntegrationCtkConformanceTest {

    private static final Path CTK = Path.of("..", "ctk").toAbsolutePath().normalize();
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path dir;

    List<Map<String, String>> run(String scenario) throws Exception {
        JsonNode mock = JSON.readTree(Files.readString(CTK.resolve("mocks/" + scenario + ".json")));
        for (JsonNode f : mock.path("folders")) Files.createDirectories(dir.resolve(f.asText()));
        Path script = Files.copy(CTK.resolve("scripts/" + scenario + ".loom"), dir.resolve(scenario + ".loom"));
        ToolRegistry registry = new ToolRegistry();
        mock.path("tools").fields().forEachRemaining(e -> registry.register(e.getKey(), answering(e.getKey(), e.getValue().asText())));
        HarnessExecutor executor = new HarnessExecutor(new LoomLoader().load(script.toString()), registry, name -> null);
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
            if (!actual.isEmpty() && actual.get(actual.size() - 1).equals(step) && step.get("kind").equals("use")) continue;
            actual.add(step);
        }
        return actual;
    }

    private static io.github.llm4j.agent.Tool answering(String name, String answer) {
        return new io.github.llm4j.agent.Tool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return "a tool the host registered";
            }

            @Override
            public String execute(Map<String, Object> args) {
                return answer;
            }
        };
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
    @ValueSource(strings = {"integration_command", "integration_credential_rejected"})
    void theScenarioMatchesItsCanonicalTrace(String scenario) throws Exception {
        assertEquals(canonical(scenario), run(scenario), scenario);
    }

    @Test
    void everyShapeOfAnIntegrationDeclarationLoadsAndPassesTheChecks() throws Exception {
        Files.createDirectories(dir.resolve("work"));
        LoomScript script = new LoomLoader().load(CTK.resolve("scripts/integration_declarations.loom").toString());

        assertThat(script.getTools()).extracting(t -> t.getName() + ":" + t.getKind()).containsExactly("Pet_get:openapi", "Pet_add:openapi", "Orders:http", "Data:workspace", "Resize:command");
        assertThat(script.getMcpServers()).extracting(m -> m.getName() + ":" + m.isRemote() + ":" + m.getIsolation()).containsExactly("Files:false:container", "Remote:true:null");

        HarnessExecutor executor = new HarnessExecutor(script, new ToolRegistry(), m -> { throw new IllegalStateException(); });
        executor.setBaseDir(dir);
        executor.setEnvLookup(name -> "set-" + name);
        executor.setHumanInterface(question -> "yes");
        List<ScriptValidator.Problem> errors = new ScriptValidator().validate(script, executor.validationContext()).stream().filter(p -> p.severity() == ScriptValidator.Severity.ERROR).toList();
        assertThat(errors).isEmpty();
    }
}
