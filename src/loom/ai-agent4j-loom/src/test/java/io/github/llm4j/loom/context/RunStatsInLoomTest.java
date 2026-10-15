package io.github.llm4j.loom.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.context.ContextWindow;
import io.github.llm4j.context.ContextWindows;
import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.observe.ObservableRun;
import io.github.llm4j.telemetry.RunStats;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R14, R16: the run's statistics are readable by an observer, and run_end carries the totals. */
class RunStatsInLoomTest {

    @TempDir
    Path dir;

    private TestLoom run(String block, int steps) {
        TestLoom l = new TestLoom(dir, TestLoom.script("tiny-model", block), steps);
        l.executor.setContextWindows(WindowLayers.none().over("t", ContextWindows.empty().with("tiny-model", new ContextWindow(3000, 500))));
        l.run();
        return l;
    }

    @Test
    void anObserverReadsTheStatistics() {
        TestLoom l = run("", 5);
        ObservableRun view = l.executor.observable();
        RunStats s = view.stats();
        assertThat(s.totalModelCalls()).isEqualTo(6);
        assertThat(s.totalToolCalls()).isEqualTo(5);
        assertThat(s.tool("lookup").successRate()).hasValue(1.0);
        assertThat(s.model("tiny-model").calls()).isEqualTo(6);
        assertThat(s.model("tiny-model").promptTokens()).isPositive();
    }

    @Test
    void theStatisticsEqualTheEventsAddedUp() {
        TestLoom l = run("context { compact: drop_oldest  keep_recent: 2  max_compactions: 50 }", 14);
        long modelEvents;
        long toolEvents;
        synchronized (l.trace) {
            modelEvents = l.trace.stream().filter(e -> e.type().equals(TraceEvent.MODEL_CALL)).count();
            toolEvents = l.trace.stream().filter(e -> e.type().equals(TraceEvent.TOOL_CALL)).count();
        }
        assertThat(l.executor.stats().totalModelCalls()).isEqualTo(modelEvents);
        assertThat(l.executor.stats().totalToolCalls()).isEqualTo(toolEvents);
    }

    @Test
    void runEndCarriesTheTotals() {
        TestLoom l = run("context { compact: drop_oldest  keep_recent: 2  max_compactions: 50 }", 14);
        TraceEvent end;
        synchronized (l.trace) {
            end = l.trace.stream().filter(e -> e.type().equals(TraceEvent.RUN_END)).findFirst().orElseThrow();
        }
        Map<String, Object> d = end.data();
        assertThat(((Number) d.get("model_calls")).longValue()).isEqualTo(15);
        assertThat(((Number) d.get("tool_calls")).longValue()).isEqualTo(14);
        assertThat(((Number) d.get("tool_failures")).longValue()).isZero();
        assertThat(((Number) d.get("compactions")).longValue()).isPositive();
        assertThat(d).containsKeys("retries", "failovers");
    }

    @Test
    void aResumedRunDoesNotCountOrReportTheReplayedDelegatesAgain() {
        Path journal = dir.resolve("journal.json");
        TestLoom first = new TestLoom(dir, TestLoom.script("tiny-model", ""), 3);
        first.executor.setJournal(new io.github.llm4j.loom.runtime.FileRunJournal(journal));
        first.run();
        assertThat(first.executor.stats().totalModelCalls()).isEqualTo(4);

        TestLoom second = new TestLoom(dir, TestLoom.script("tiny-model", ""), 3);
        second.executor.setJournal(new io.github.llm4j.loom.runtime.FileRunJournal(journal));
        second.run();
        List<String> types;
        synchronized (second.trace) {
            types = second.trace.stream().map(TraceEvent::type).toList();
        }
        assertThat(types).contains(TraceEvent.DELEGATE_REPLAYED).doesNotContain(TraceEvent.MODEL_CALL, TraceEvent.TOOL_CALL);
        assertThat(second.executor.stats().totalModelCalls()).isZero();
    }
}
