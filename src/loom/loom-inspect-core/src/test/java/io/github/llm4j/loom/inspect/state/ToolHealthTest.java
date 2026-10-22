package io.github.llm4j.loom.inspect.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * R18, R19: the heuristics behind the tools-centric view. A history of calls in, a state and its
 * reason out.
 */
class ToolHealthTest {

    private static final Thresholds T = Thresholds.defaults();

    /**
     * "+" is a success, a word is a failure with that category, "-" is a call the runtime never
     * ran.
     */
    private static ToolSummary history(String... calls) {
        ToolTally t = new ToolTally("Wiki");
        int n = 0;
        for (String c : calls) {
            String at = "2026-10-14T10:00:" + String.format("%02d", ++n) + "Z";
            if (c.equals("+")) t.record("Editor", true, null, at, 100, false);
            else if (c.equals("-")) t.skipped();
            else t.record("Editor", false, c, at, 100, c.startsWith("soft:"));
        }
        return t.snapshot(T);
    }

    @Test
    void aToolNobodyHasRunIsUnused() {
        assertThat(history().state()).isEqualTo("unused");
        assertThat(history("-", "-").state()).isEqualTo("unused");
        assertThat(history("-").calls()).isEqualTo(1);
    }

    @Test
    void aToolThatKeepsSucceedingIsHealthy() {
        ToolSummary s = history("+", "+", "+", "+", "+", "+");
        assertThat(s.state()).isEqualTo("healthy");
        assertThat(s.successRate()).hasValue(1.0);
    }

    @Test
    void neverSucceedingOverThreeCallsIsFailing_butTwoCallsAreOnlyDegraded() {
        ToolSummary two = history("timeout", "timeout");
        assertThat(two.state()).isEqualTo("degraded");
        assertThat(two.reason()).contains("last call failed");
        ToolSummary three = history("timeout", "timeout", "not_found");
        assertThat(three.state()).isEqualTo("failing");
        assertThat(three.reason()).contains("never succeeded").contains("3 calls");
    }

    @Test
    void aToolThatOnceWorkedButFailedItsLastThreeInARowIsFailing() {
        ToolSummary s = history("+", "+", "+", "+", "+", "+", "timeout", "timeout", "timeout");
        assertThat(s.state()).isEqualTo("failing");
        assertThat(s.reason()).contains("last 3 calls failed").contains("timeout");
        assertThat(s.consecutiveFailures()).isEqualTo(3);
    }

    @Test
    void twoFailuresInARowAmongSuccessesAreNotFailing() {
        ToolSummary s = history("+", "+", "+", "+", "+", "+", "+", "+", "timeout", "timeout");
        assertThat(s.state()).isEqualTo("healthy");
    }

    @Test
    void theRateOverTheLastTenCallsDecides_atTheBoundaries() {
        // 10 calls: 3 successes (30%) is failing; 4 (40%) is degraded; 7 (70%) is healthy; 6 (60%)
        // is degraded
        assertThat(history("+", "x", "+", "x", "x", "+", "x", "x", "x", "x").state())
                .isEqualTo("failing");
        assertThat(history("x", "x", "+", "x", "x", "+", "+", "x", "x", "+").state())
                .isEqualTo("degraded");
        assertThat(history("x", "+", "+", "x", "+", "+", "x", "+", "+", "+").state())
                .isEqualTo("healthy");
        assertThat(history("x", "+", "x", "+", "x", "+", "x", "+", "+", "+").state())
                .isEqualTo("degraded");
        assertThat(history("x", "+", "x", "+", "x", "+", "x", "+", "+", "+").reason())
                .contains("6 of 10");
    }

    @Test
    void fewerThanFiveCallsAreNotJudgedByRate_exceptThatAFailedLastCallIsDegraded() {
        assertThat(history("+", "x", "+", "+").state()).isEqualTo("healthy");
        assertThat(history("+", "+", "+", "x").state()).isEqualTo("degraded");
        assertThat(history("x", "+").state()).isEqualTo("healthy");
    }

    @Test
    void onlyTheLastTenCallsCountForTheRecentRate_whileTheOverallRateKeepsEverything() {
        String[] calls = new String[30];
        for (int i = 0; i < 20; i++) calls[i] = "timeout";
        for (int i = 20; i < 30; i++) calls[i] = "+";
        ToolSummary s = history(calls);
        assertThat(s.state()).isEqualTo("healthy");
        assertThat(s.successRate().getAsDouble()).isEqualTo(10 / 30.0);
        assertThat(s.recentRate().getAsDouble()).isEqualTo(1.0);
    }

    @Test
    void callsThatNeverRanAreNeitherSuccessesNorFailures() {
        ToolSummary s = history("-", "+", "-", "+", "-");
        assertThat(s.calls()).isEqualTo(5);
        assertThat(s.ran()).isEqualTo(2);
        assertThat(s.successRate()).hasValue(1.0);
    }

    @Test
    void theRateThresholdsComeFromTheRules() {
        ToolTally t = new ToolTally("Wiki");
        for (int i = 0; i < 10; i++)
            t.record("A", i >= 2, "timeout", "2026-10-14T10:00:00Z", 10, false); // 8 of 10
        assertThat(t.snapshot(T).state()).isEqualTo("healthy");
        Thresholds strict = new Thresholds(4, 3, 2, 4, 3, 60, 0.85, 0.95, 1, 4, 10, 0.9, 10, 5.0);
        assertThat(t.snapshot(strict).state()).isEqualTo("degraded");
    }

    // ── what the summary carries ────────────────────────────────────────────────

    @Test
    void failuresAreGroupedByCategory_softFailuresCounted_andLastFailureAndSuccessKept() {
        ToolSummary s = history("+", "timeout", "soft:not_found", "timeout", "+", "timeout");
        assertThat(s.failures()).containsEntry("timeout", 3L).containsEntry("soft:not_found", 1L);
        assertThat(s.softFailures()).isEqualTo(1);
        assertThat(s.lastFailureCategory()).isEqualTo("timeout");
        assertThat(s.lastFailureAt()).isEqualTo("2026-10-14T10:00:06Z");
        assertThat(s.lastSuccessAt()).isEqualTo("2026-10-14T10:00:05Z");
        assertThat(s.consecutiveFailures()).isEqualTo(1);
    }

    @Test
    void theAgentBreakdownShowsWhoFailsWithIt() {
        ToolTally t = new ToolTally("Wiki");
        for (int i = 0; i < 5; i++)
            t.record("Editor", true, null, "2026-10-14T10:00:00Z", 10, false);
        for (int i = 0; i < 4; i++)
            t.record("Writer", false, "not_found", "2026-10-14T10:00:00Z", 10, false);
        ToolSummary s = t.snapshot(T);
        assertThat(s.byAgent().get("Editor")).isEqualTo(new ToolSummary.Tally(5, 5, 0));
        assertThat(s.byAgent().get("Writer")).isEqualTo(new ToolSummary.Tally(4, 0, 4));
    }

    @Test
    void latencyIsAHistogramWhosePercentilesAreBucketBounds() {
        ToolTally t = new ToolTally("Wiki");
        for (int i = 0; i < 90; i++) t.record("A", true, null, "x", 40, false); // the 50 ms bucket
        for (int i = 0; i < 10; i++)
            t.record("A", true, null, "x", 3000, false); // the 5000 ms bucket
        ToolSummary s = t.snapshot(T);
        assertThat(s.p50Ms()).isEqualTo(50);
        assertThat(s.p95Ms()).isEqualTo(5000);
        assertThat(s.histogram()).hasSize(ToolSummary.BOUNDS.length + 1);
        assertThat(s.histogram().stream().mapToLong(Long::longValue).sum()).isEqualTo(100);
    }

    @Test
    void theMapForTheViewHasEveryField() {
        Map<String, Object> m = history("+", "timeout", "timeout", "timeout").toHealthMap();
        assertThat(m)
                .containsKeys(
                        "state",
                        "reason",
                        "recentRate",
                        "recentCalls",
                        "overallRate",
                        "consecutiveFailures",
                        "lastFailure",
                        "lastSuccessAt",
                        "failures",
                        "byAgent");
        assertThat(m.get("state")).isEqualTo("failing");
        assertThat(((Map<?, ?>) m.get("lastFailure")).get("category")).isEqualTo("timeout");
    }
}
