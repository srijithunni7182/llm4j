package io.github.loom.ctk;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The canonical intervention scenarios: the files are well formed and the comparator holds a runtime to the intervention contract. */
class InterventionConformanceTest {

    private static final Path CTK = Path.of("").toAbsolutePath();
    private final ObjectMapper json = new ObjectMapper();

    private List<String> scenarios() throws IOException {
        try (Stream<Path> files = Files.list(CTK.resolve("interventions"))) {
            return files.map(p -> p.getFileName().toString().replace(".json", "")).sorted().toList();
        }
    }

    private ExecutionTrace expected(String name) throws IOException {
        return json.readValue(Files.readString(CTK.resolve("traces").resolve(name + ".json")), ExecutionTrace.class);
    }

    @Test
    void everyScenarioHasAScriptATraceAndAFixtureThatSaysWhatHappens() throws IOException {
        List<String> all = scenarios();
        assertTrue(all.size() >= 15, all.toString());
        for (String name : all) {
            assertTrue(Files.exists(CTK.resolve("scripts").resolve(name + ".loom")), name + " has a script");
            assertEquals(name + ".loom", expected(name).scriptName());
            JsonNode fixture = json.readTree(Files.readString(CTK.resolve("interventions").resolve(name + ".json")));
            assertTrue(fixture.has("description") && fixture.has("expect"), name);
            assertTrue(fixture.get("expect").has("run") || fixture.get("expect").has("loadError"), name + " says how the run ends");
            for (JsonNode i : fixture.path("interventions")) {
                assertTrue(List.of("steer", "cancel", "wrap_up").contains(i.get("kind").asText()), name);
                assertTrue(i.get("atCall").asInt() >= 1, name);
            }
        }
    }

    @Test
    void theTableOfDefaultsHasAScenarioForEachRow() throws IOException {
        List<String> all = scenarios();
        for (String row : List.of("user_cancel_ask", "wrap_up", "steer", "on_stopped_skip", "conclude", "cancel_back", "timeout_retry", "timeout_changed",
                "sibling_discard", "shutdown_suspend", "safety_ask", "budget_partial")) {
            assertTrue(all.contains("intervention_" + row), row);
        }
        for (String negative : List.of("retry_refused_when_changed", "cancelled_is_not_success", "rewind_undeclared")) {
            assertTrue(all.contains("intervention_" + negative), negative);
        }
    }

    @Test
    void anInterventionStepIsComparedByItsPayload() throws IOException {
        ExecutionTrace e = expected("intervention_timeout_retry");
        assertTrue(TraceComparator.compareTraces(e, e).passed());
        List<TraceStep> steps = new ArrayList<>(e.steps());
        TraceStep stop = steps.get(1);
        assertEquals("stop", stop.kind());
        steps.set(1, new TraceStep("stop", stop.agentName(), stop.payload().replace("RETRY", "USE_PARTIAL"), null, null, null, null));
        ConformanceResult r = TraceComparator.compareTraces(new ExecutionTrace(e.scriptName(), e.workflowName(), steps), e);
        assertFalse(r.passed(), "a different outcome is not conformant");
        assertTrue(r.differences().get(0).contains("payload"), r.differences().toString());
    }

    @Test
    void aSteerAndARewindAreComparedToo() throws IOException {
        ExecutionTrace steer = expected("intervention_steer");
        List<TraceStep> steps = new ArrayList<>(steer.steps());
        TraceStep s = steps.get(1);
        assertEquals("steer", s.kind());
        steps.set(1, new TraceStep("steer", s.agentName(), "something else", null, null, null, null));
        assertFalse(TraceComparator.compareTraces(new ExecutionTrace(steer.scriptName(), steer.workflowName(), steps), steer).passed());

        ExecutionTrace back = expected("intervention_cancel_back");
        List<TraceStep> backSteps = new ArrayList<>(back.steps());
        TraceStep rewind = backSteps.get(3);
        assertEquals("rewind", rewind.kind());
        backSteps.set(3, new TraceStep("rewind", null, rewind.payload().replace("collected", "later"), null, null, null, null));
        assertFalse(TraceComparator.compareTraces(new ExecutionTrace(back.scriptName(), back.workflowName(), backSteps), back).passed());
    }

    @Test
    void theOutcomeOfAStopAndWhyAreInTheCanonicalPayload() throws IOException {
        TraceStep stop = expected("intervention_timeout_changed").steps().get(1);
        assertEquals("{\"kind\":\"cancel\",\"outcome\":\"USE_PARTIAL\",\"reason\":\"timeout\"}", stop.payload());
        assertEquals("{\"kind\":\"cancel\",\"outcome\":\"SKIP\",\"reason\":\"safety\"}", expected("intervention_safety_ask").steps().get(1).payload());
    }
}
