package io.github.llm4j.loom.depth;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.TraceEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R36: a guard event in the trace names what the guard did (as the audit line does), so an observer can count them without reading text. */
class GuardEventNamesTest {

    @TempDir
    Path dir;

    private static List<TraceEvent> guards(List<TraceEvent> trace) {
        synchronized (trace) {
            return trace.stream().filter(e -> TraceEvent.GUARD.equals(e.type())).toList();
        }
    }

    private List<TraceEvent> run(String guard) {
        Harness h = new Harness(dir).answers(Harness.call("Lookup", "{\"q\": \"order\"}"), Harness.done("Reply sent to asha@example.com"));
        h.tools.register("Lookup", Harness.recording("Lookup", new ArrayList<>(), "Customer: Ravi, ravi@shop.in"));
        HarnessExecutor e = h.ready(GuardTest.script(guard));
        List<TraceEvent> trace = java.util.Collections.synchronizedList(new ArrayList<>());
        e.addTraceListener(trace::add);
        e.executeWorkflow("Main", Map.of("task", GuardTest.TASK));
        return guards(trace);
    }

    @Test
    void aMaskedValueIsATraceEventNamedPiiMasked() {
        List<TraceEvent> g = run("pii: mask");
        assertThat(g).isNotEmpty().allSatisfy(e -> assertThat(e.data()).containsEntry("event", "pii_masked"));
    }

    @Test
    void aBlockedTaskIsNamedPiiBlocked() {
        List<TraceEvent> g = run("pii: block");
        assertThat(g).anySatisfy(e -> assertThat(e.data()).containsEntry("event", "pii_blocked"));
    }

    @Test
    void aWarningIsNamedPiiDetected() {
        List<TraceEvent> g = run("pii: warn");
        assertThat(g).anySatisfy(e -> assertThat(e.data()).containsEntry("event", "pii_detected"));
    }

    @Test
    void theEventNamesCarryNoPersonalData() {
        for (TraceEvent e : run("pii: mask")) assertThat(String.valueOf(e.data())).doesNotContain("asha@example.com").doesNotContain("ravi@shop.in");
    }
}
