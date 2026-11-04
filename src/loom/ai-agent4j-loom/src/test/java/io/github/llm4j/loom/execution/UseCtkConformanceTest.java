package io.github.llm4j.loom.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Runs the canonical {@code use_tool} and {@code approve_step} scenarios of the Loom Conformance
 * Test Kit ({@code loom/ctk}) and holds the trace to the canonical one: which tool was used, what
 * the person answered, and the notes the script wrote. The fixtures in {@code mocks/} say what the
 * tool and the person answer.
 */
class UseCtkConformanceTest {

    private static final Path CTK = Path.of("..", "ctk").toAbsolutePath().normalize();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static List<Map<String, String>> run(String scenario) throws Exception {
        JsonNode mock = JSON.readTree(Files.readString(CTK.resolve("mocks/" + scenario + ".json")));
        JsonNode notify = mock.get("Notify");
        String input = notify.fieldNames().next();
        String answer = notify.get(input).asText();
        String human = mock.has("human") ? mock.get("human").asText() : "yes";
        LoomScript script =
                new LoomParser(new Lexer(Files.readString(CTK.resolve("scripts/" + scenario + ".loom"))).tokenize())
                        .parseScript();
        ToolRegistry tools = new ToolRegistry();
        tools.register(
                "Notify",
                new Tool() {
                    @Override
                    public String getName() {
                        return "Notify";
                    }

                    @Override
                    public String getDescription() {
                        return "posts a note";
                    }

                    @Override
                    public String execute(Map<String, Object> args) {
                        return answer;
                    }
                });
        HarnessExecutor executor = new HarnessExecutor(script, tools, name -> null);
        executor.setHumanInterface(message -> human);
        List<TraceEvent> events = new ArrayList<>();
        executor.addTraceListener(events::add);
        executor.initialize();
        executor.executeWorkflow("main", new HashMap<>(Map.of("text", input)));
        List<Map<String, String>> actual = new ArrayList<>();
        for (TraceEvent ev : events) {
            Map<String, String> step = new java.util.LinkedHashMap<>();
            if (ev.type().equals(TraceEvent.TOOL_CALL) && "use".equals(ev.data().get("by"))) {
                step.put("kind", "use");
                step.put("tool", String.valueOf(ev.data().get("tool")));
            } else if (ev.type().equals(TraceEvent.APPROVAL)
                    && ev.text().startsWith("approval ")
                    && !ev.text().startsWith("approval requested")) {
                step.put("kind", "approval");
                step.put("tool", String.valueOf(ev.data().get("tool")));
                step.put("answer", ev.text().startsWith("approval granted") ? "yes" : "no");
            } else if (ev.type().equals(TraceEvent.NOTE)) {
                step.put("kind", "note");
                step.put("text", ev.text());
            } else {
                continue;
            }
            actual.add(step);
        }
        return actual;
    }

    private static List<Map<String, String>> canonical(String scenario) throws Exception {
        List<Map<String, String>> expected = new ArrayList<>();
        for (JsonNode s : JSON.readTree(Files.readString(CTK.resolve("traces/" + scenario + ".json"))).get("steps")) {
            Map<String, String> step = new java.util.LinkedHashMap<>();
            s.fields().forEachRemaining(f -> step.put(f.getKey(), f.getValue().asText()));
            expected.add(step);
        }
        return expected;
    }

    @Test
    void useToolMatchesTheCanonicalTrace() throws Exception {
        assertEquals(canonical("use_tool"), run("use_tool"));
    }

    @Test
    void approveStepMatchesTheCanonicalTrace() throws Exception {
        assertEquals(canonical("approve_step"), run("approve_step"));
    }
}
