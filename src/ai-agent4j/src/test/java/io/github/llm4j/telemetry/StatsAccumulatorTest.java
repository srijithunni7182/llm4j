package io.github.llm4j.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.model.LLMResponse.FinishReason;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Statistics are the events added up (R15). */
class StatsAccumulatorTest {

    private static ModelCallEvent ok(String model, long ms, int attempts, long waited, long in, long out, String cost, FinishReason fr, List<String> failovers) {
        return ModelCallEvent.builder().model(model).durationMillis(ms).waitedMillis(waited).attempts(attempts).failovers(failovers)
                .promptTokens(in).completionTokens(out).totalTokens(in + out).cost(cost == null ? null : new BigDecimal(cost)).finishReason(fr).build();
    }

    private static ToolCallEvent tool(String name, long ms, long chars) {
        return ToolCallEvent.builder().tool(name).durationMillis(ms).resultChars((int) chars).build();
    }

    @Test
    void nothingRecordedIsAnEmptyButUsableResult() {
        RunStats s = new StatsAccumulator().snapshot();
        assertThat(s.models()).isEmpty();
        assertThat(s.tools()).isEmpty();
        assertThat(s.totalModelCalls()).isZero();
        assertThat(s.tool("search").calls()).isZero(); // asking for a tool never called is safe
        assertThat(s.tool("search").successRate()).isEmpty(); // not NaN, not zero
        assertThat(s.model("m").calls()).isZero();
        assertThat(s.model("m").latency().isEmpty()).isTrue();
        assertThat(RunStats.EMPTY).isEqualTo(s);
    }

    @Test
    void modelCallsAreAddedUpPerModel() {
        StatsAccumulator a = new StatsAccumulator();
        a.onModelCall(ok("flash", 100, 1, 0, 1_000, 50, "0.001", FinishReason.STOP, List.of()));
        a.onModelCall(ok("flash", 300, 3, 250, 2_000, 150, "0.002", FinishReason.LENGTH, List.of("primary")));
        a.onModelCall(ok("pro", 900, 1, 0, 500, 20, null, FinishReason.STOP, List.of()));
        RunStats s = a.snapshot();
        ModelStats flash = s.model("flash");
        assertThat(flash.calls()).isEqualTo(2);
        assertThat(flash.promptTokens()).isEqualTo(3_000);
        assertThat(flash.completionTokens()).isEqualTo(200);
        assertThat(flash.cost()).isEqualByComparingTo("0.003");
        assertThat(flash.retries()).isEqualTo(2);
        assertThat(flash.failovers()).isEqualTo(1);
        assertThat(flash.waitedMillis()).isEqualTo(250);
        assertThat(flash.truncated()).isEqualTo(1);
        assertThat(flash.finishReasons()).containsEntry(FinishReason.STOP, 1L).containsEntry(FinishReason.LENGTH, 1L);
        assertThat(flash.latency().count()).isEqualTo(2);
        assertThat(flash.latency().max()).isEqualTo(300);
        assertThat(flash.successRate()).hasValue(1.0);
        assertThat(s.model("pro").cost()).as("no call reported a cost").isNull();
        assertThat(s.totalModelCalls()).isEqualTo(3);
        assertThat(s.totalRetries()).isEqualTo(2);
        assertThat(s.totalFailovers()).isEqualTo(1);
        assertThat(s.truncatedAnswers()).isEqualTo(1);
    }

    @Test
    void failedModelCallsAreCountedByCategory() {
        StatsAccumulator a = new StatsAccumulator();
        a.onModelCall(ok("flash", 100, 1, 0, 10, 1, null, FinishReason.STOP, List.of()));
        a.onModelCall(ModelCallEvent.builder().model("flash").durationMillis(5_000).failed(ErrorCategory.TIMEOUT, "SocketTimeoutException").build());
        a.onModelCall(ModelCallEvent.builder().model("flash").durationMillis(40).failed(ErrorCategory.RATE_LIMITED, "RateLimitException").build());
        a.onModelCall(ModelCallEvent.builder().model("flash").durationMillis(60).failed(ErrorCategory.TIMEOUT, "TimeoutException").build());
        ModelStats m = a.snapshot().model("flash");
        assertThat(m.calls()).isEqualTo(4);
        assertThat(m.failed()).isEqualTo(3);
        assertThat(m.failures()).containsEntry(ErrorCategory.TIMEOUT, 2L).containsEntry(ErrorCategory.RATE_LIMITED, 1L).hasSize(2);
        assertThat(m.successRate()).hasValue(0.25);
        assertThat(m.latency().count()).as("failed calls have a latency too").isEqualTo(4);
        assertThat(m.finishReasons()).as("a failed call has no finish reason").hasSize(1);
        assertThat(a.snapshot().totalModelFailures()).isEqualTo(3);
    }

    @Test
    void aCallWithoutAModelNameIsFiledUnderTheRequestedOneOrUnknown() {
        StatsAccumulator a = new StatsAccumulator();
        a.onModelCall(ModelCallEvent.builder().requestedModel("asked-for").failed(ErrorCategory.OTHER, "X").build());
        a.onModelCall(ModelCallEvent.builder().failed(ErrorCategory.OTHER, "X").build());
        assertThat(a.snapshot().models()).containsOnlyKeys("asked-for", "unknown");
    }

    @Test
    void toolCallsAreAddedUpPerTool() {
        StatsAccumulator a = new StatsAccumulator();
        a.onToolCall(tool("search", 100, 500));
        a.onToolCall(tool("search", 300, 700));
        a.onToolCall(ToolCallEvent.builder().tool("search").durationMillis(900).softFailure(ErrorCategory.NOT_FOUND).resultChars(30).build());
        a.onToolCall(ToolCallEvent.builder().tool("search").durationMillis(2_000).outcome(ToolOutcome.EXECUTION_ERROR, ErrorCategory.TIMEOUT, "SocketTimeoutException").build());
        a.onToolCall(tool("calc", 5, 2));
        ToolStats s = a.snapshot().tool("search");
        assertThat(s.calls()).isEqualTo(4);
        assertThat(s.successes()).isEqualTo(2);
        assertThat(s.failed()).isEqualTo(2);
        assertThat(s.softFailures()).isEqualTo(1);
        assertThat(s.failures()).containsEntry(ErrorCategory.NOT_FOUND, 1L).containsEntry(ErrorCategory.TIMEOUT, 1L);
        assertThat(s.successRate()).hasValue(0.5);
        assertThat(s.resultChars()).isEqualTo(1_230);
        assertThat(s.latency().count()).isEqualTo(4);
        assertThat(s.latency().max()).isEqualTo(2_000);
        assertThat(a.snapshot().tool("calc").successRate()).hasValue(1.0);
        assertThat(a.snapshot().totalToolCalls()).isEqualTo(5);
        assertThat(a.snapshot().totalToolFailures()).isEqualTo(2);
    }

    @Test
    void callsThatNeverReachedTheToolAreCountedButDoNotSkewItsLatency() {
        StatsAccumulator a = new StatsAccumulator();
        a.onToolCall(tool("search", 200, 10));
        a.onToolCall(ToolCallEvent.builder().tool("search").outcome(ToolOutcome.DUPLICATE_BLOCKED, ErrorCategory.REPEAT_BLOCKED, null).build());
        a.onToolCall(ToolCallEvent.builder().tool("search").outcome(ToolOutcome.REJECTED_BY_HUMAN, ErrorCategory.REJECTED_BY_HUMAN, null).build());
        ToolStats s = a.snapshot().tool("search");
        assertThat(s.calls()).isEqualTo(3);
        assertThat(s.failures()).containsEntry(ErrorCategory.REPEAT_BLOCKED, 1L).containsEntry(ErrorCategory.REJECTED_BY_HUMAN, 1L);
        assertThat(s.latency().count()).isEqualTo(1);
        assertThat(s.latency().p50()).isEqualTo(200);
    }

    @Test
    void aSnapshotIsConsistentAndNotAffectedByLaterEvents() {
        StatsAccumulator a = new StatsAccumulator();
        a.onToolCall(tool("t", 1, 1));
        RunStats before = a.snapshot();
        a.onToolCall(tool("t", 1, 1));
        assertThat(before.tool("t").calls()).isEqualTo(1);
        assertThat(a.snapshot().tool("t").calls()).isEqualTo(2);
    }

    @Test
    void resetClears() {
        StatsAccumulator a = new StatsAccumulator();
        a.onToolCall(tool("t", 1, 1));
        a.onModelCall(ok("m", 1, 1, 0, 1, 1, null, FinishReason.STOP, List.of()));
        a.reset();
        assertThat(a.snapshot()).isEqualTo(RunStats.EMPTY);
    }

    @Test
    void theMapsAreReadOnlyAndInAStableOrder() {
        StatsAccumulator a = new StatsAccumulator();
        a.onToolCall(tool("zeta", 1, 1));
        a.onToolCall(tool("alpha", 1, 1));
        RunStats s = a.snapshot();
        assertThat(s.tools().keySet()).containsExactly("alpha", "zeta");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> s.tools().put("x", s.tool("alpha"))).isInstanceOf(UnsupportedOperationException.class);
    }
}
