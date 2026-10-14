package io.github.llm4j.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.telemetry.ErrorCategory;
import io.github.llm4j.telemetry.ModelCallEvent;
import io.github.llm4j.telemetry.RunStats;
import io.github.llm4j.telemetry.ToolCallEvent;
import io.github.llm4j.telemetry.ToolOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** A listener that keeps statistics across runs and threads (R17). */
class StatsCollectorTest {

    @Test
    void itIsAnAgentListenerThatCountsWhatItIsTold() {
        StatsCollector c = new StatsCollector();
        AgentEventListener listener = c;
        listener.onToolCall(ToolCallEvent.builder().tool("search").durationMillis(120).resultChars(300).build());
        listener.onToolCall(ToolCallEvent.builder().tool("search").softFailure(ErrorCategory.NOT_FOUND).durationMillis(80).build());
        listener.onModelCall(ModelCallEvent.builder().model("m").durationMillis(900).promptTokens(100).completionTokens(10).totalTokens(110).build());
        RunStats s = c.snapshot();
        assertThat(s.tool("search").calls()).isEqualTo(2);
        assertThat(s.tool("search").successRate()).hasValue(0.5);
        assertThat(s.model("m").calls()).isEqualTo(1);
    }

    @Test
    void theOriginalCallbacksAreNoOps() {
        StatsCollector c = new StatsCollector();
        c.onThought("t");
        c.onAction("a", "i");
        c.onObservation("o");
        assertThat(c.snapshot()).isEqualTo(RunStats.EMPTY);
    }

    @Test
    void resetStartsAgain() {
        StatsCollector c = new StatsCollector();
        c.onToolCall(ToolCallEvent.builder().tool("t").build());
        c.reset();
        assertThat(c.snapshot().totalToolCalls()).isZero();
    }

    @Test
    void manyThreadsGiveTheSumAndASnapshotIsAlwaysConsistent() throws Exception {
        StatsCollector c = new StatsCollector();
        int threads = 16, perThread = 2_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> work = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int id = t;
                work.add(pool.submit((Callable<Integer>) () -> {
                    go.await();
                    for (int i = 0; i < perThread; i++) {
                        c.onToolCall(ToolCallEvent.builder().tool("t" + (id % 3)).durationMillis(i % 50).resultChars(1)
                                .outcome(i % 10 == 0 ? ToolOutcome.EXECUTION_ERROR : ToolOutcome.EXECUTED, i % 10 == 0 ? ErrorCategory.TIMEOUT : null, null).build());
                        c.onModelCall(ModelCallEvent.builder().model("m").durationMillis(i % 70).promptTokens(2).completionTokens(1).totalTokens(3).build());
                    }
                    return perThread;
                }));
            }
            // snapshots taken while the others write must each add up on their own
            go.countDown();
            for (int k = 0; k < 200; k++) {
                RunStats s = c.snapshot();
                long byTool = s.tools().values().stream().mapToLong(x -> x.calls()).sum();
                assertThat(byTool).isEqualTo(s.totalToolCalls());
                assertThat(s.tools().values().stream().mapToLong(x -> x.successes() + x.failed()).sum()).isEqualTo(byTool);
            }
            for (Future<Integer> f : work) f.get();
        } finally {
            pool.shutdownNow();
        }
        RunStats s = c.snapshot();
        assertThat(s.totalToolCalls()).isEqualTo((long) threads * perThread);
        assertThat(s.totalModelCalls()).isEqualTo((long) threads * perThread);
        assertThat(s.model("m").promptTokens()).isEqualTo(2L * threads * perThread);
        assertThat(s.totalToolFailures()).isEqualTo((long) threads * perThread / 10);
    }
}
