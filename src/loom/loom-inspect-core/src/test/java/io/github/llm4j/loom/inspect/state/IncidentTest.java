package io.github.llm4j.loom.inspect.state;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.inspect.otel.Ids;
import io.github.llm4j.loom.inspect.otel.LogRec;
import io.github.llm4j.loom.inspect.otel.OtlpJson;
import io.github.llm4j.loom.inspect.otel.Resource;
import io.github.llm4j.loom.inspect.otel.Semconv;
import io.github.llm4j.loom.inspect.otel.SpanRec;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The rules, replayed: a recorded stream of what a run did in, the incidents out. No clock, no run; only what the process would send.
 * The streams are the misbehaviour the soak workflow stages on purpose (a tool that fails, a loop that does not converge, a draft with
 * invented figures, a pause).
 */
class IncidentTest {

    private static final String RUN = "r-inc";
    private static final String TRACE = Ids.traceId(RUN);
    private static final Resource PROC = Resource.of("p", "i_1");
    private static final long T0 = OtlpJson.nanos(Instant.parse("2026-10-11T14:00:00Z"));

    private final RunRegistry reg = new RunRegistry();
    private long seq;
    private int keys;

    private static long at(int s) {
        return T0 + s * 1_000_000_000L;
    }

    private void log(int second, String event, String body, Object... kv) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.EVENT_NAME, event);
        a.put(Semconv.LOOM_SEQ, ++seq);
        for (int i = 0; i < kv.length; i += 2) a.put((String) kv[i], kv[i + 1]);
        reg.applyLogs(PROC, List.of(new LogRec(at(second), LogRec.INFO, body, a, TRACE, null)));
    }

    private void start() {
        log(0, Semconv.EV_RUN_START, "w", Semconv.LOOM_RUN_ID, RUN, Semconv.LOOM_WORKFLOW, "W");
    }

    private void toolStart(int second, String agent, String tool, String step, String hash) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.EVENT_NAME, Semconv.EV_SPAN_START);
        a.put(Semconv.LOOM_SEQ, ++seq);
        a.put(Semconv.LOOM_SPAN_KIND, Semconv.KIND_TOOL);
        a.put(Semconv.GEN_AI_AGENT_NAME, agent);
        a.put(Semconv.GEN_AI_TOOL_NAME, tool);
        a.put(Semconv.LOOM_STEP, step);
        a.put(Semconv.LOOM_ACTION_HASH, hash);
        reg.applyLogs(PROC, List.of(new LogRec(at(second), LogRec.INFO, "execute_tool " + tool, a, TRACE, Ids.spanId(RUN, "t" + (++keys)))));
    }

    private void toolEnd(int second, String agent, String tool, String step, boolean error) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.LOOM_SPAN_KIND, Semconv.KIND_TOOL);
        a.put(Semconv.GEN_AI_AGENT_NAME, agent);
        a.put(Semconv.GEN_AI_TOOL_NAME, tool);
        a.put(Semconv.LOOM_STEP, step);
        reg.applySpans(PROC, List.of(new SpanRec(TRACE, Ids.spanId(RUN, "te" + (++keys)), null, "execute_tool " + tool, SpanRec.CLIENT, at(second), at(second + 1), a,
                error ? SpanRec.STATUS_ERROR : SpanRec.STATUS_OK, null, List.of())));
    }

    private void answer(int second, String agent, String step, String hash, long figures, long ungrounded, String examples) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.LOOM_SPAN_KIND, Semconv.KIND_AGENT);
        a.put(Semconv.GEN_AI_AGENT_NAME, agent);
        a.put(Semconv.LOOM_STEP, step);
        a.put(Semconv.LOOM_OUTPUT_HASH, hash);
        a.put(Semconv.LOOM_GROUNDING_FIGURES, figures);
        a.put(Semconv.LOOM_GROUNDING_UNGROUNDED, ungrounded);
        if (examples != null) a.put(Semconv.LOOM_GROUNDING_EXAMPLES, examples);
        reg.applySpans(PROC, List.of(new SpanRec(TRACE, Ids.spanId(RUN, "a" + (++keys)), null, "invoke_agent " + agent, SpanRec.CLIENT, at(second), at(second + 1), a,
                SpanRec.STATUS_OK, null, List.of())));
    }

    private RunState run() {
        return reg.find("p", TRACE).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> incidents() {
        return (List<Map<String, Object>>) run().view(OtlpJson.instant(at(10))).get("incidents");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> evidence(Map<String, Object> incident) {
        return ((List<Map<String, Object>>) incident.get("evidence")).get(0);
    }

    @Test
    void theSameToolCallFourTimesInAStep_roundsOfALoopCountedTogether_isALoop() {
        start();
        for (int round = 1; round <= 3; round++) toolStart(round, "Editor", "Wiki", "W/s1/r" + round + ".0", "h1");
        assertThat(incidents()).isEmpty();
        toolStart(4, "Editor", "Wiki", "W/s1/r4.0", "h1");
        Map<String, Object> inc = incidents().get(0);
        assertThat(inc).containsEntry("kind", "LOOPING").containsEntry("severity", "warn").containsEntry("state", "open").containsEntry("agent", "Editor");
        assertThat(evidence(inc)).containsEntry("rule", "same_action").containsEntry("measured", 4).containsEntry("threshold", 4);
    }

    @Test
    void differentCallsOrDifferentAgentsAreNotALoop() {
        start();
        for (int i = 1; i <= 6; i++) toolStart(i, "Editor", "Wiki", "W/s1", "h" + i);
        for (int i = 1; i <= 3; i++) toolStart(i, "Other", "Wiki", "W/s1", "same");
        assertThat(incidents()).isEmpty();
    }

    @Test
    void theSameAnswerThreeTimesInARowIsALoop_andADifferentAnswerEndsIt() {
        start();
        answer(1, "Lead", "W/s1/r1.1", "aa", 0, 0, null);
        answer(2, "Lead", "W/s1/r2.1", "aa", 0, 0, null);
        assertThat(incidents()).isEmpty();
        answer(3, "Lead", "W/s1/r3.1", "aa", 0, 0, null);
        assertThat(incidents()).hasSize(1);
        assertThat(evidence(incidents().get(0))).containsEntry("rule", "same_output").containsEntry("measured", 3);
        answer(4, "Lead", "W/s1/r4.1", "bb", 0, 0, null);
        assertThat(incidents().get(0)).containsEntry("state", "closed").containsEntry("closeReason", "the answer changed");
    }

    @Test
    void figuresWithNoSourceAreASuspectOutput_warnThenHigh_andOneIsNotEnough() {
        start();
        answer(1, "Writer", "W/s2", "h1", 5, 1, "87%");
        assertThat(incidents()).isEmpty();
        answer(2, "Writer", "W/s2", "h2", 5, 2, "87%, 2019");
        assertThat(incidents().get(0)).containsEntry("kind", "SUSPECT_OUTPUT").containsEntry("severity", "warn");
        answer(3, "Writer", "W/s2", "h3", 6, 4, "87%, 2019, 1200, 12.5%");
        Map<String, Object> inc = incidents().get(0);
        assertThat(inc).containsEntry("severity", "high");
        assertThat(String.valueOf(evidence(inc).get("detail"))).contains("4 of 6 figures").contains("nothing this step was given or fetched").contains("2019");
        assertThat(String.valueOf(inc.get("title"))).contains("Suspect output");
    }

    @Test
    void aToolThatFailsThreeTimesInARowIsFailing_andItsNextSuccessEndsIt() {
        start();
        toolEnd(1, "Competitor", "Example", "W/s0", true);
        toolEnd(2, "Competitor", "Example", "W/s0", true);
        assertThat(incidents()).isEmpty();
        toolEnd(3, "Competitor", "Example", "W/s0", true);
        assertThat(incidents().get(0)).containsEntry("kind", "FAILED").containsEntry("severity", "warn");
        toolEnd(4, "Competitor", "Example", "W/s0", false);
        assertThat(incidents().get(0)).containsEntry("state", "closed");
        toolEnd(5, "Competitor", "Example", "W/s0", true); // the streak starts again from one
        assertThat(incidents().stream().filter(i -> "open".equals(i.get("state")))).isEmpty();
    }

    @Test
    void aPauseByALimitIsInformation_aLongOneAWarning_andItEndsWhenTheRunGoesOn() {
        start();
        log(5, Semconv.EV_RUN_SUSPENDED, "run paused: RATE_LIMIT", Semconv.LOOM_SUSPEND_REASON, "RATE_LIMIT", Semconv.LOOM_RESUME_AT, "2026-10-11T14:10:00Z");
        Map<String, Object> inc = incidents().get(0); // viewed 5 seconds in
        assertThat(inc).containsEntry("kind", "PAUSED").containsEntry("severity", "info");
        assertThat(String.valueOf(evidence(inc).get("detail"))).contains("not a failure");
        @SuppressWarnings("unchecked")
        Map<String, Object> later = ((List<Map<String, Object>>) run().view(OtlpJson.instant(at(5 + 90))).get("incidents")).get(0);
        assertThat(later).containsEntry("severity", "warn");
        assertThat(evidence(later)).containsEntry("rule", "paused_longer_than").containsEntry("threshold", "60s");
        toolStart(200, "Editor", "Wiki", "W/s3", "h");
        assertThat(incidents().get(0)).containsEntry("state", "closed").containsEntry("closeReason", "the run resumed");
    }

    @Test
    void aSleepOrAWaitForAPersonIsNotAnIncident() {
        start();
        log(5, Semconv.EV_RUN_SUSPENDED, "sleep", Semconv.LOOM_SUSPEND_REASON, "SLEEP", Semconv.LOOM_RESUME_AT, "2026-10-11T14:10:00Z");
        log(6, Semconv.EV_RUN_SUSPENDED, "human", Semconv.LOOM_SUSPEND_REASON, "HUMAN");
        assertThat(incidents()).isEmpty();
    }

    @Test
    void aBudgetWarningIsAWarning_andRunningOutIsHigh() {
        start();
        log(3, Semconv.EV_BUDGET, "WARN run: spent 80% of limits", "loom.data.budget", "run", "loom.data.kind", "WARN");
        assertThat(incidents().get(0)).containsEntry("kind", "BUDGET").containsEntry("severity", "warn");
        log(4, Semconv.EV_BUDGET, "EXHAUSTED run: spent 100% of limits", "loom.data.budget", "run", "loom.data.kind", "EXHAUSTED");
        assertThat(incidents()).hasSize(1);
        assertThat(incidents().get(0)).containsEntry("severity", "high");
    }

    @Test
    void whenTheRunEndsWhatWasOpenIsOver_butASuspendedRunKeepsItsLoop() {
        start();
        for (int i = 1; i <= 4; i++) toolStart(i, "Editor", "Wiki", "W/s1", "h1");
        log(8, Semconv.EV_RUN_END, "suspended", Semconv.LOOM_RUN_STATUS, Semconv.STATUS_SUSPENDED);
        assertThat(incidents().get(0)).containsEntry("state", "open");
        log(9, Semconv.EV_RUN_START, "w", Semconv.LOOM_RUN_ID, RUN, Semconv.LOOM_WORKFLOW, "W");
        log(10, Semconv.EV_RUN_END, "done", Semconv.LOOM_RUN_STATUS, Semconv.STATUS_COMPLETED);
        assertThat(incidents().get(0)).containsEntry("state", "closed").containsEntry("closeReason", "the run ended");
    }

    @Test
    void theSummaryCarriesTheOpenCountAndTheWorstSeverity_forTheTabs() {
        start();
        for (int i = 1; i <= 4; i++) toolStart(i, "Editor", "Wiki", "W/s1", "h1");
        answer(6, "Writer", "W/s2", "h", 6, 5, "1, 2");
        Map<String, Object> s = run().summary();
        assertThat(s).containsEntry("incidents", 2).containsEntry("worstIncident", "high");
    }

    @Test
    void thresholdsCanBeChanged_whichIsHowAScriptWillSayWhatIsNormalForAnAgent() {
        start();
        run().setThresholds(new Thresholds(2, 3, 2, 4, 3, 60));
        toolStart(1, "Editor", "Wiki", "W/s1", "h1");
        toolStart(2, "Editor", "Wiki", "W/s1", "h1");
        assertThat(incidents()).hasSize(1);
    }

    @Test
    void aRepeatedRecordIsNotCountedTwice() {
        start();
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.EVENT_NAME, Semconv.EV_SPAN_START);
        a.put(Semconv.LOOM_SEQ, ++seq);
        a.put(Semconv.LOOM_SPAN_KIND, Semconv.KIND_TOOL);
        a.put(Semconv.GEN_AI_AGENT_NAME, "Editor");
        a.put(Semconv.GEN_AI_TOOL_NAME, "Wiki");
        a.put(Semconv.LOOM_STEP, "W/s1");
        a.put(Semconv.LOOM_ACTION_HASH, "h1");
        LogRec r = new LogRec(at(1), LogRec.INFO, "x", a, TRACE, Ids.spanId(RUN, "dup"));
        for (int i = 0; i < 6; i++) reg.applyLogs(PROC, List.of(r)); // sent again after lost acknowledgements
        assertThat(incidents()).isEmpty();
    }

    @Test
    void aCallBlockedAsARepeatNeitherBreaksNorStartsAFailingStreak() {
        start();
        toolEnd(1, "Competitor", "Example", "W/s0", true);
        toolEnd(2, "Competitor", "Example", "W/s0", true);
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.LOOM_SPAN_KIND, Semconv.KIND_TOOL);
        a.put(Semconv.GEN_AI_AGENT_NAME, "Competitor");
        a.put(Semconv.GEN_AI_TOOL_NAME, "Example");
        a.put(Semconv.LOOM_TOOL_NO_RESULT, true);
        reg.applySpans(PROC, List.of(new SpanRec(TRACE, Ids.spanId(RUN, "blocked"), null, "execute_tool Example", SpanRec.CLIENT, at(3), at(4), a, SpanRec.STATUS_UNSET, null, List.of())));
        assertThat(incidents()).isEmpty();      // it did not count as a third failure
        toolEnd(5, "Competitor", "Example", "W/s0", true);
        assertThat(incidents().get(0)).containsEntry("kind", "FAILED"); // and it did not reset the streak either
    }
}
