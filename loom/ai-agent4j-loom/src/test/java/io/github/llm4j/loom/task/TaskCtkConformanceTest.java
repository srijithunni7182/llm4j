package io.github.llm4j.loom.task;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.tools.CanonicalArgs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Runs the canonical task scenarios of the Loom Conformance Test Kit ({@code loom/ctk}) through this runtime and holds its trace to
 * the canonical one: which task ran, with which arguments, in which order, bound to which variable. The CTK's own runner is a stub, so
 * for the reference runtime this is where the contract is actually executed.
 */
class TaskCtkConformanceTest {

    private static final Path CTK = Path.of("..", "ctk").toAbsolutePath().normalize();
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Tasks whose behaviour is the fixture: for given arguments (sorted-key JSON), the result map. */
    private static List<Task> fixtureTasks() throws IOException {
        JsonNode fixture = JSON.readTree(Files.readString(CTK.resolve("mocks/task_basic.json")));
        List<Task> tasks = new ArrayList<>();
        fixture.fieldNames().forEachRemaining(name -> tasks.add(Task.pure(name, ctx -> {
            String args = CanonicalArgs.json(ctx.args());
            JsonNode response = fixture.get(name).get(args);
            if (response == null) throw new IllegalStateException("the fixture has no response for " + name + " " + args);
            Map<String, Object> m = JSON.readValue(response.asText(), new com.fasterxml.jackson.core.type.TypeReference<>() { });
            TaskResult result = TaskResult.outcome((String) m.remove("outcome"));
            if (m.containsKey("reason")) result = result.reason((String) m.remove("reason"));
            if (m.containsKey("value")) result = result.withValue(m.remove("value"));
            return result.withAll(m);
        })));
        return tasks;
    }

    private static List<Map<String, String>> steps(JsonNode trace) {
        List<Map<String, String>> out = new ArrayList<>();
        for (JsonNode s : trace.get("steps")) {
            Map<String, String> step = new java.util.LinkedHashMap<>();
            for (String f : List.of("kind", "taskName", "payload", "outputVariable")) step.put(f, s.has(f) ? s.get(f).asText() : null);
            out.add(step);
        }
        return out;
    }

    @ParameterizedTest
    @ValueSource(strings = {"task_basic", "task_rejected"})
    void theTraceMatchesTheCanonicalOne(String scenario) throws Exception {
        TaskHarness h = new TaskHarness();
        for (Task t : fixtureTasks()) h.tasks.register(t);
        String source = Files.readString(CTK.resolve("scripts/" + scenario + ".loom"));
        HarnessExecutor e = h.executor(source);
        e.initialize();
        e.executeWorkflow("main", new HashMap<>());

        List<Map<String, String>> actual = new ArrayList<>();
        for (TraceEvent ev : h.traceOf(TraceEvent.TASK_START)) {
            Map<String, String> step = new java.util.LinkedHashMap<>();
            step.put("kind", "task");
            step.put("taskName", String.valueOf(ev.data().get("task")));
            step.put("payload", String.valueOf(ev.data().get("args")));
            step.put("outputVariable", String.valueOf(ev.data().get("variable")));
            actual.add(step);
        }
        JsonNode expected = JSON.readTree(Files.readString(CTK.resolve("traces/" + scenario + ".json")));
        assertEquals(steps(expected), actual);
        assertEquals(0, h.modelCalls.get(), "a task scenario uses no model");
    }

    @Test
    void theScenariosTakeTheBranchesTheirNamesPromise() throws Exception {
        TaskHarness h = new TaskHarness();
        for (Task t : fixtureTasks()) h.tasks.register(t);
        HarnessExecutor e = h.executor(Files.readString(CTK.resolve("scripts/task_basic.loom")));
        e.initialize();
        e.executeWorkflow("main", new HashMap<>());
        assertEquals("rcpt-1", ((Map<?, ?>) e.getContext().getVariable("receipt")).get("value"));

        TaskHarness other = new TaskHarness();
        for (Task t : fixtureTasks()) other.tasks.register(t);
        HarnessExecutor r = other.executor(Files.readString(CTK.resolve("scripts/task_rejected.loom")));
        r.initialize();
        r.executeWorkflow("main", new HashMap<>());
        assertEquals("over the limit", ((Map<?, ?>) r.getContext().getVariable("verdict")).get("reason"));
        assertEquals("ok", ((Map<?, ?>) r.getContext().getVariable("ticket")).get("outcome"));
    }
}
