package io.github.llm4j.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.StatsCollector;
import java.net.SocketTimeoutException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A result carries the statistics of its own run (design §4.6, R15, R18). */
class RunStatsIntegrationTest {

    private static TestTool mixedTool(TestClock clock) {
        return new TestTool("t", (a, n) -> {
            clock.advance(100L * n);
            if (n == 2) return "Error: not found";
            if (n == 3) throw new SocketTimeoutException("slow");
            return "ok " + n;
        });
    }

    @ParameterizedTest(name = "native={0}")
    @ValueSource(booleans = {true, false})
    void theResultHasTheStatisticsOfItsRun(boolean nativeLoop) {
        TestClock clock = new TestClock();
        TelemetryListener l = new TelemetryListener();
        AgentResult r = ReActAgent.builder().llmClient(Scripts.toolSteps(nativeLoop, "t", 4)).addTool(mixedTool(clock)).clock(clock).addListener(l)
                .maxIterations(20).build().run("Do it.");
        ToolStats s = r.getStats().tool("t");
        assertThat(s.calls()).isEqualTo(4);
        assertThat(s.successes()).isEqualTo(2);
        assertThat(s.failed()).isEqualTo(2);
        assertThat(s.softFailures()).isEqualTo(1);
        assertThat(s.failures()).containsEntry(ErrorCategory.NOT_FOUND, 1L).containsEntry(ErrorCategory.TIMEOUT, 1L).hasSize(2);
        assertThat(s.successRate()).hasValue(0.5);
        assertThat(s.latency().count()).isEqualTo(4);
        assertThat(s.latency().min()).isEqualTo(100);
        assertThat(s.latency().max()).isEqualTo(400);
        assertThat(s.latency().p50()).isEqualTo(200);
        assertThat(r.getStats().totalToolCalls()).isEqualTo(4);
    }

    @ParameterizedTest(name = "native={0}")
    @ValueSource(booleans = {true, false})
    void theStatisticsAreExactlyTheEventsAddedUp(boolean nativeLoop) {
        TestClock clock = new TestClock();
        TelemetryListener l = new TelemetryListener();
        AgentResult r = ReActAgent.builder().llmClient(Scripts.toolSteps(nativeLoop, "t", 4)).addTool(mixedTool(clock)).clock(clock).addListener(l)
                .maxIterations(20).build().run("Do it.");
        StatsAccumulator replay = new StatsAccumulator();
        l.tools.forEach(replay::onToolCall);
        assertThat(r.getStats().tools()).isEqualTo(replay.snapshot().tools());
    }

    @ParameterizedTest(name = "native={0}")
    @ValueSource(booleans = {true, false})
    void aCollectorOnTheAgentSeesTheSameNumbersAsTheResult(boolean nativeLoop) {
        TestClock clock = new TestClock();
        StatsCollector c = new StatsCollector();
        AgentResult r = ReActAgent.builder().llmClient(Scripts.toolSteps(nativeLoop, "t", 4)).addTool(mixedTool(clock)).clock(clock).addListener(c)
                .maxIterations(20).build().run("Do it.");
        assertThat(c.snapshot().tools()).isEqualTo(r.getStats().tools());
    }

    @ParameterizedTest(name = "native={0}")
    @ValueSource(booleans = {true, false})
    void aRunThatNeverCalledATool(boolean nativeLoop) {
        AgentResult r = ReActAgent.builder().llmClient(Scripts.by(nativeLoop, n -> Scripts.done(nativeLoop))).addTool(TestTool.returning("t", "x")).build().run("Hi.");
        assertThat(r.getStats()).isNotNull();
        assertThat(r.getStats().totalToolCalls()).isZero();
        assertThat(r.getStats().tool("t").successRate()).isEmpty();
    }

    @ParameterizedTest(name = "native={0}")
    @ValueSource(booleans = {true, false})
    void everyKindOfResultHasStats(boolean nativeLoop) {
        // the agent runs out of iterations: a result with no final answer
        AgentResult exhausted = ReActAgent.builder().llmClient(Scripts.toolSteps(nativeLoop, "t", 50)).addTool(TestTool.returning("t", "ok")).maxIterations(3).build().run("Go.");
        assertThat(exhausted.isCompleted()).isFalse();
        assertThat(exhausted.getStats().tool("t").calls()).isEqualTo(3);
        // a result made when the budget ran out
        io.github.llm4j.budget.Budget budget = io.github.llm4j.budget.Budget.builder().calls(2).build();
        AgentResult budgeted = ReActAgent.builder().llmClient(Scripts.toolSteps(nativeLoop, "t", 50)).addTool(TestTool.returning("t", "ok")).budget(budget).maxIterations(20).build().run("Go.");
        assertThat(budgeted.budgetExhausted()).isTrue();
        assertThat(budgeted.getStats()).isNotNull();
        assertThat(budgeted.getStats().tool("t").calls()).isGreaterThan(0);
    }

    @ParameterizedTest(name = "native={0}")
    @ValueSource(booleans = {true, false})
    void aCopyOfAResultKeepsItsStats(boolean nativeLoop) {
        AgentResult r = ReActAgent.builder().llmClient(Scripts.oneToolStep(nativeLoop, "t", "x")).addTool(TestTool.returning("t", "ok")).build().run("Go.");
        assertThat(r.toBuilder().build().getStats()).isEqualTo(r.getStats());
    }
}
