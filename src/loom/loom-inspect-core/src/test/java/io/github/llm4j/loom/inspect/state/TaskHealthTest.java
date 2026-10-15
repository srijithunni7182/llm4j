package io.github.llm4j.loom.inspect.state;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.inspect.otel.Ids;
import io.github.llm4j.loom.inspect.otel.Resource;
import io.github.llm4j.loom.inspect.otel.Semconv;
import io.github.llm4j.loom.inspect.otel.SpanRec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** R25: tasks have the health account tools have, kept apart from tools, in the view and in the cross-run rollup. */
class TaskHealthTest {

    private final TaskFailureFixtures f = new TaskFailureFixtures();

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(String key) {
        return (List<Map<String, Object>>) f.state.view().get(key);
    }

    @Test
    void theViewListsTasksWithRateLatencyAndHealth() {
        for (int i = 0; i < 4; i++) f.task(i + 1, "Pay", 200, true, null);
        f.task(5, "Pay", 900, false, "server_error");
        Map<String, Object> pay = rows("tasks").get(0);
        assertThat(pay).containsEntry("name", "Pay").containsEntry("calls", 5L).containsEntry("errors", 1L).containsEntry("successes", 4L);
        assertThat((double) pay.get("successRate")).isEqualTo(0.8);
        assertThat((Map<String, Object>) pay.get("failures")).containsEntry("server_error", 1L);
        assertThat(((Map<String, Object>) pay.get("health")).get("state")).isEqualTo("healthy");
        assertThat((long) pay.get("p95Ms")).isGreaterThan(0);
    }

    @Test
    void aTaskThatKeepsFailingIsFailing() {
        for (int i = 0; i < 3; i++) f.task(i + 1, "Pay", 100, false, "timeout");
        assertThat(((Map<String, Object>) rows("tasks").get(0).get("health")).get("state")).isEqualTo("failing");
    }

    @Test
    void aTaskIsNotAToolEvenWithTheSameName() {
        f.task(1, "Search", 100, false, "timeout");
        assertThat(rows("tasks")).hasSize(1);
        assertThat(rows("tools")).isEmpty();
        List<ToolSummary> all = f.state.toolSummaries();
        assertThat(all).singleElement().satisfies(s -> assertThat(s.kind()).isEqualTo("task"));
    }

    @Test
    void theRollupKeepsATaskAndAToolOfTheSameNameApart() {
        RunRegistry registry = new RunRegistry();
        String trace = Ids.traceId("r1");
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put(Semconv.LOOM_SPAN_KIND, Semconv.KIND_TOOL);
        tool.put(Semconv.GEN_AI_AGENT_NAME, "A");
        tool.put(Semconv.GEN_AI_TOOL_NAME, "Search");
        Map<String, Object> task = new LinkedHashMap<>();
        task.put(Semconv.LOOM_SPAN_KIND, Semconv.KIND_TASK);
        task.put(Semconv.LOOM_TASK, "Search");
        task.put(Semconv.LOOM_TASK_OUTCOME, "ok");
        long end = TaskFailureFixtures.at(1);
        registry.applySpans(Resource.of("shop", "i_1"), List.of(
                new SpanRec(trace, Ids.spanId("r1", "a"), null, "execute_tool Search", SpanRec.CLIENT, end - 1000, end, tool, SpanRec.STATUS_OK, null, List.of()),
                new SpanRec(trace, Ids.spanId("r1", "b"), null, "loom.task Search", SpanRec.INTERNAL, end - 1000, end, task, SpanRec.STATUS_OK, null, List.of())));
        assertThat(registry.toolRollup(null)).extracting(ToolSummary::kind).containsExactlyInAnyOrder("tool", "task");
    }

    @Test
    void mergingTasksFromTwoRunsAddsTheirCounts() {
        TaskFailureFixtures g = new TaskFailureFixtures();
        for (int i = 0; i < 3; i++) f.task(i + 1, "Pay", 100, true, null);
        for (int i = 0; i < 2; i++) g.task(i + 1, "Pay", 100, false, "timeout");
        ToolSummary merged = f.state.toolSummaries().get(0).merge(g.state.toolSummaries().get(0), Thresholds.defaults());
        assertThat(merged.kind()).isEqualTo("task");
        assertThat(merged.calls()).isEqualTo(5);
        assertThat(merged.successes()).isEqualTo(3);
    }

    @Test
    void aReplayedTaskWithNoSpanCountsForNothing() {
        // a task replayed from the journal ran in an earlier process; its health was counted there
        assertThat(rows("tasks")).isEmpty();
    }
}
