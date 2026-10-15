package io.github.llm4j.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.StatsCollector;
import org.junit.jupiter.api.Test;

/** R16, R17 at scale: a thousand calls of each kind through one collector; totals are exact and the retained samples are bounded. */
class StatsSoakTest {

    @Test
    void aThousandToolCallsAndModelCallsAreCountedExactly() {
        StatsCollector collector = new StatsCollector();
        int steps = 1_000;
        AgentResult result = ReActAgent.builder()
                .llmClient(Scripts.toolSteps(true, "search", steps))
                .addTool(TestTool.returning("search", "ok"))
                .maxIterations(steps + 5)
                .addListener(collector)
                .build().run("Go.");
        RunStats s = collector.snapshot();
        assertThat(s.totalToolCalls()).isEqualTo(steps);
        assertThat(s.totalModelCalls()).isEqualTo(steps + 1);
        assertThat(result.getStats().totalToolCalls()).isEqualTo(steps);
        assertThat(s.model("m").promptTokens()).isEqualTo(10L * (steps + 1));
    }

    @Test
    void theReservoirStaysBoundedWhileTheCountStaysExact() {
        LatencyRecorder rec = new LatencyRecorder();
        int n = 100_000;
        for (int i = 1; i <= n; i++) rec.add(i % 1_000);
        LatencyStats l = rec.snapshot();
        assertThat(l.count()).isEqualTo(n);
        assertThat(rec.retained()).isLessThanOrEqualTo(10_000);
        assertThat(l.max()).isEqualTo(999);
        assertThat(l.p50()).isBetween(400L, 600L);
    }
}
