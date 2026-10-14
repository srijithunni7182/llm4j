package io.github.llm4j.loom.inspect.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What the run page draws that the view did not yet carry: the thresholds in force for an agent's context, and each tool's and task's recent outcomes and latency histogram. */
class RunViewForPageTest {

    private final ContextFixtures c = new ContextFixtures();
    private final TaskFailureFixtures t = new TaskFailureFixtures();
    private final SecurityFixtures s = new SecurityFixtures();

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(RunState state, String key) {
        return (List<Map<String, Object>>) state.view().get(key);
    }

    @Test
    void anAgentsContextSaysWhereItsWarningAndHighMarksAre() {
        c.context(1, "Editor", 500, 1200L, 1000L);
        assertThat(c.context("Editor")).containsEntry("warnAt", 0.85).containsEntry("highAt", 0.95);
    }

    @Test
    void theMarksAreTheOnesInForceForThatAgent() {
        c.state.setRules(Map.of("Editor", new InspectRules(null, null, null, null, null, null, null, null,
                new InspectRules.Telemetry(0.7, 0.9, null, null, null, null, null, null))));
        c.context(1, "Editor", 500, 1200L, 1000L);
        c.context(2, "Writer", 500, 1200L, 1000L);
        assertThat(c.context("Editor")).containsEntry("warnAt", 0.7).containsEntry("highAt", 0.9);
        assertThat(c.context("Writer")).containsEntry("warnAt", 0.85).containsEntry("highAt", 0.95);
    }

    @Test
    void aToolRowCarriesItsLastTenOutcomesAndItsLatencyHistogram() {
        for (int i = 1; i <= 12; i++) s.tool(i, "A", "Fetch", "untrusted");
        s.failed(13, "A", "Fetch", "untrusted", "timeout", false);
        Map<String, Object> row = rows(s.state, "tools").get(0);
        @SuppressWarnings("unchecked")
        List<String> recent = (List<String>) row.get("recent");
        assertThat(recent).hasSize(10);
        assertThat(recent.get(9)).isEqualTo("timeout");
        assertThat(recent.get(0)).isEmpty();
        @SuppressWarnings("unchecked")
        List<Number> histogram = (List<Number>) row.get("histogram");
        assertThat(histogram.stream().mapToLong(Number::longValue).sum()).isEqualTo(13);
        assertThat((List<?>) row.get("histogramBounds")).hasSize(histogram.size() - 1);
    }

    @Test
    void aTaskRowCarriesTheSame() {
        t.task(1, "Pay", 200, true, null);
        t.task(2, "Pay", 300, false, "server_error");
        Map<String, Object> row = rows(t.state, "tasks").get(0);
        @SuppressWarnings("unchecked")
        List<String> recent = (List<String>) row.get("recent");
        assertThat(recent).containsExactly("", "server_error");
        assertThat((List<?>) row.get("histogram")).isNotEmpty();
    }

    @Test
    void theOldFieldsAreStillThere() {
        s.tool(1, "A", "Fetch", "untrusted");
        assertThat(rows(s.state, "tools").get(0)).containsKeys("name", "calls", "errors", "inFlight", "avgMillis", "successes", "successRate", "failures", "p50Ms", "p95Ms", "health");
    }
}
