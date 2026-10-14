package io.github.llm4j.loom.inspect.state;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** R20: summaries from several runs merge into one account of a tool. */
class ToolSummaryMergeTest {

    private static final Thresholds T = Thresholds.defaults();

    private static ToolSummary of(String agent, int ok, int bad, String category, long millis) {
        ToolTally t = new ToolTally("Wiki");
        for (int i = 0; i < bad; i++) t.record(agent, false, category, "2026-10-14T10:01:0" + (i % 9) + "Z", millis, false);
        for (int i = 0; i < ok; i++) t.record(agent, true, null, "2026-10-14T10:00:0" + (i % 9) + "Z", millis, false);
        return t.snapshot(T);
    }

    @Test
    void countsCategoriesAgentsAndHistogramsAdd() {
        ToolSummary a = of("Editor", 6, 2, "timeout", 40);
        ToolSummary b = of("Writer", 1, 3, "not_found", 3000);
        ToolSummary m = a.merge(b, T);
        assertThat(m.calls()).isEqualTo(12);
        assertThat(m.ran()).isEqualTo(12);
        assertThat(m.successes()).isEqualTo(7);
        assertThat(m.failures()).containsEntry("timeout", 2L).containsEntry("not_found", 3L);
        assertThat(m.byAgent()).containsKeys("Editor", "Writer");
        assertThat(m.byAgent().get("Writer").calls()).isEqualTo(4);
        assertThat(m.histogram().stream().mapToLong(Long::longValue).sum()).isEqualTo(12);
    }

    @Test
    void mergingIsCommutativeAndAssociativeOnTheNumbers() {
        ToolSummary a = of("A", 3, 1, "x", 10);
        ToolSummary b = of("B", 2, 2, "y", 700);
        ToolSummary c = of("C", 0, 5, "z", 6000);
        ToolSummary left = a.merge(b, T).merge(c, T);
        ToolSummary right = c.merge(b.merge(a, T), T);
        assertThat(left.calls()).isEqualTo(right.calls());
        assertThat(left.successes()).isEqualTo(right.successes());
        assertThat(left.failures()).isEqualTo(right.failures());
        assertThat(left.byAgent()).isEqualTo(right.byAgent());
        assertThat(left.histogram()).isEqualTo(right.histogram());
        assertThat(left.state()).isEqualTo(right.state());
        assertThat(left.p95Ms()).isEqualTo(right.p95Ms());
    }

    @Test
    void theMergedStateIsTheWorstOfTheParts() {
        ToolSummary healthy = of("A", 9, 0, null, 10);
        ToolSummary failing = of("B", 0, 4, "timeout", 10);
        assertThat(healthy.merge(failing, T).state()).isEqualTo("failing");
        assertThat(failing.merge(healthy, T).state()).isEqualTo("failing");
        assertThat(failing.state()).isEqualTo("failing");
    }

    @Test
    void healthyPartsCanStillBeDegradedTogetherWhenTheOverallRateIsLow() {
        ToolSummary a = of("A", 3, 1, "timeout", 10);  // 75%: healthy by itself (4 calls)
        ToolSummary b = of("B", 2, 2, "timeout", 10);  // 50% over 4 calls
        assertThat(a.state()).isEqualTo("healthy");
        ToolSummary m = a.merge(b, T);                 // 5 of 8 = 62%
        assertThat(m.state()).isEqualTo("degraded");
        assertThat(m.reason()).contains("5 of 8");
    }

    @Test
    void neverSucceedingAcrossRunsIsFailing_evenIfNoSingleRunHadThreeCalls() {
        ToolSummary a = of("A", 0, 2, "timeout", 10);
        ToolSummary b = of("B", 0, 2, "timeout", 10);
        assertThat(a.state()).isEqualTo("degraded");
        assertThat(a.merge(b, T).state()).isEqualTo("failing");
        assertThat(a.merge(b, T).reason()).contains("never succeeded").contains("4 calls");
    }

    @Test
    void lastFailureAndLastSuccessTakeTheLaterTime() {
        ToolSummary a = of("A", 1, 1, "timeout", 10); // success 10:00:00, failure 10:01:00
        ToolTally t = new ToolTally("Wiki");
        t.record("B", false, "not_found", "2026-10-14T11:00:00Z", 10, false);
        t.record("B", true, null, "2026-10-14T09:00:00Z", 10, false);
        ToolSummary m = a.merge(t.snapshot(T), T);
        assertThat(m.lastFailureAt()).isEqualTo("2026-10-14T11:00:00Z");
        assertThat(m.lastFailureCategory()).isEqualTo("not_found");
        assertThat(m.lastSuccessAt()).isEqualTo("2026-10-14T10:00:00Z");
    }

    @Test
    void mergingTwoSummariesOfDifferentToolsIsAnError() {
        ToolTally other = new ToolTally("Other");
        other.record("A", true, null, "x", 1, false);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> of("A", 1, 0, null, 10).merge(other.snapshot(T), T));
    }
}
