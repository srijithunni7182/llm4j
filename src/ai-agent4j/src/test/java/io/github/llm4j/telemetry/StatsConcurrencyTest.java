package io.github.llm4j.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.StatsCollector;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * R17 with real agents: many runs on a pool, one shared collector; its totals equal the sum of the
 * runs' own statistics.
 */
class StatsConcurrencyTest {

    @Test
    void manyAgentsSharingOneCollectorAddUpExactly() throws Exception {
        StatsCollector shared = new StatsCollector();
        int runs = 64;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<AgentResult>> futures = new ArrayList<>();
            for (int i = 0; i < runs; i++) {
                boolean nativeLoop = i % 2 == 0;
                int steps = 1 + i % 5;
                futures.add(
                        pool.submit(
                                () ->
                                        ReActAgent.builder()
                                                .llmClient(
                                                        Scripts.toolSteps(
                                                                nativeLoop, "search", steps))
                                                .addTool(TestTool.returning("search", "ok"))
                                                .maxIterations(20)
                                                .addListener(shared)
                                                .build()
                                                .run("Go.")));
            }
            long models = 0, tools = 0, prompt = 0;
            for (Future<AgentResult> f : futures) {
                RunStats s = f.get().getStats();
                models += s.totalModelCalls();
                tools += s.totalToolCalls();
                prompt += s.model("m").promptTokens();
            }
            RunStats total = shared.snapshot();
            assertThat(total.totalModelCalls()).isEqualTo(models);
            assertThat(total.totalToolCalls()).isEqualTo(tools);
            assertThat(total.model("m").promptTokens()).isEqualTo(prompt);
            assertThat(total.tool("search").successRate()).hasValue(1.0);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void callTracesOnDifferentThreadsDoNotMix() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<CallTrace.Totals>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int attempts = t + 1;
                futures.add(
                        pool.submit(
                                () -> {
                                    CallTrace.begin();
                                    for (int i = 0; i < attempts; i++) {
                                        CallTrace.attempt();
                                        Thread.yield();
                                    }
                                    return CallTrace.end();
                                }));
            }
            for (int t = 0; t < threads; t++)
                assertThat(futures.get(t).get().attempts()).isEqualTo(t + 1);
        } finally {
            pool.shutdownNow();
        }
    }
}
