package io.github.loom.ctk;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The canonical task scenarios: the files are well formed and the comparator holds a runtime to the task contract. */
class TaskConformanceTest {

    private static final Path CTK = Path.of("").toAbsolutePath();
    private final ObjectMapper json = new ObjectMapper();

    private ExecutionTrace expected(String name) throws IOException {
        return json.readValue(Files.readString(CTK.resolve("traces").resolve(name + ".json")), ExecutionTrace.class);
    }

    @Test
    void theCanonicalTracesDescribeTaskSteps() throws IOException {
        for (String name : List.of("task_basic", "task_rejected")) {
            assertTrue(Files.exists(CTK.resolve("scripts").resolve(name + ".loom")), name);
            ExecutionTrace t = expected(name);
            assertEquals(name + ".loom", t.scriptName());
            assertEquals(2, t.steps().size());
            for (TraceStep s : t.steps()) {
                assertEquals("task", s.kind());
                assertNotNull(s.taskName());
                assertNull(s.agentName(), "a task has no agent");
                assertNotNull(s.payload());
                assertNotNull(s.outputVariable());
            }
        }
        assertEquals("RefundPolicy", expected("task_basic").steps().get(0).taskName());
        assertEquals("Escalate", expected("task_rejected").steps().get(1).taskName());
    }

    @Test
    void theFixtureDefinesWhatEachTaskReturnsForGivenArguments() throws IOException {
        MockAgentServer mocks = new FixtureMockAgentServer(CTK.resolve("mocks"));
        assertEquals("{\"outcome\":\"approved\"}", mocks.getResponse("RefundPolicy", "{\"amount\":40}"));
        assertTrue(mocks.getResponse("RefundPolicy", "{\"amount\":90}").contains("over the limit"));
        assertTrue(mocks.getResponse("IssueRefund", "{\"amount\":40}").contains("rcpt-1"));
    }

    @Test
    void identicalTraceConforms() throws IOException {
        ConformanceResult r = TraceComparator.compareTraces(expected("task_basic"), expected("task_basic"));
        assertTrue(r.passed(), r.differences().toString());
    }

    @Test
    void aDifferentTaskOrDifferentArgumentsIsNotConformant() throws IOException {
        ExecutionTrace e = expected("task_basic");
        TraceStep first = e.steps().get(0);
        ExecutionTrace wrongTask = new ExecutionTrace(e.scriptName(), e.workflowName(), List.of(
                new TraceStep("task", "LaxPolicy", null, first.payload(), first.outputVariable(), null, null, null), e.steps().get(1)));
        ConformanceResult a = TraceComparator.compareTraces(wrongTask, e);
        assertFalse(a.passed());
        assertTrue(a.differences().stream().anyMatch(d -> d.contains("taskName: LaxPolicy vs RefundPolicy")), a.differences().toString());

        ExecutionTrace wrongArgs = new ExecutionTrace(e.scriptName(), e.workflowName(), List.of(
                new TraceStep("task", "RefundPolicy", null, "{\"amount\":400}", first.outputVariable(), null, null, null), e.steps().get(1)));
        ConformanceResult b = TraceComparator.compareTraces(wrongArgs, e);
        assertFalse(b.passed());
        assertTrue(b.differences().stream().anyMatch(d -> d.contains("payload")), b.differences().toString());
    }

    @Test
    void aTaskStepWhereADelegateIsExpectedIsNotConformant() throws IOException {
        ExecutionTrace e = expected("task_basic");
        TraceStep asDelegate = new TraceStep("delegate", null, "TestAgent", "x", "verdict", null, null, null);
        ConformanceResult r = TraceComparator.compareTraces(
                new ExecutionTrace(e.scriptName(), e.workflowName(), List.of(asDelegate, e.steps().get(1))), e);
        assertFalse(r.passed());
    }

    @Test
    void nonTaskStepsStillSerializeWithoutATaskName() throws IOException {
        String out = json.writeValueAsString(new TraceStep("delegate", "A", "p", "v", null, null, null));
        assertFalse(out.contains("taskName"), out);
        TraceStep back = json.readValue("{\"kind\":\"delegate\",\"agentName\":\"A\"}", TraceStep.class);
        assertNull(back.taskName());
    }
}
