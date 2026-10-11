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

/** The run state, fed only with OpenTelemetry-shaped records. */
class RunStateTest {

    private static final String RUN = "r-7f3a";
    private static final String TRACE = Ids.traceId(RUN);
    private static final Resource PROC = Resource.of("getviral-prod", "i_1");
    private static final long T0 = OtlpJson.nanos(Instant.parse("2026-10-11T14:02:00Z"));

    private long seq;

    private static long at(int seconds) {
        return T0 + seconds * 1_000_000_000L;
    }

    private LogRec log(int second, String event, String body, Object... kv) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.EVENT_NAME, event);
        a.put(Semconv.LOOM_SEQ, ++seq);
        for (int i = 0; i < kv.length; i += 2) a.put((String) kv[i], kv[i + 1]);
        return new LogRec(at(second), LogRec.INFO, body, a, TRACE, null);
    }

    private LogRec start(int second, String key, String kind, String name, String agent, String tool, String step, String... extra) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.EVENT_NAME, Semconv.EV_SPAN_START);
        a.put(Semconv.LOOM_SEQ, ++seq);
        a.put(Semconv.LOOM_SPAN_KIND, kind);
        if (agent != null) a.put(Semconv.GEN_AI_AGENT_NAME, agent);
        if (tool != null) a.put(Semconv.GEN_AI_TOOL_NAME, tool);
        a.put(Semconv.LOOM_STEP, step);
        for (int i = 0; i < extra.length; i += 2) a.put(extra[i], extra[i + 1]);
        return new LogRec(at(second), LogRec.INFO, name, a, TRACE, Ids.spanId(RUN, key));
    }

    private SpanRec agentSpan(String key, String agent, int from, int to, long tokens, long calls, String cost) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.LOOM_SPAN_KIND, Semconv.KIND_AGENT);
        a.put(Semconv.GEN_AI_AGENT_NAME, agent);
        a.put(Semconv.LOOM_USAGE_TOTAL_TOKENS, tokens);
        a.put(Semconv.LOOM_USAGE_CALLS, calls);
        a.put(Semconv.LOOM_COST_USD, cost);
        return new SpanRec(TRACE, Ids.spanId(RUN, key), null, "invoke_agent " + agent, SpanRec.CLIENT, at(from), at(to), a, SpanRec.STATUS_OK, null, List.of());
    }

    private SpanRec toolSpan(String key, String agent, String tool, int from, int to, boolean error) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.LOOM_SPAN_KIND, Semconv.KIND_TOOL);
        a.put(Semconv.GEN_AI_AGENT_NAME, agent);
        a.put(Semconv.GEN_AI_TOOL_NAME, tool);
        return new SpanRec(TRACE, Ids.spanId(RUN, key), null, "execute_tool " + tool, SpanRec.CLIENT, at(from), at(to), a,
                error ? SpanRec.STATUS_ERROR : SpanRec.STATUS_OK, null, List.of());
    }

    private RunRegistry registry(LogRec... logs) {
        RunRegistry reg = new RunRegistry();
        reg.applyLogs(PROC, List.of(logs));
        return reg;
    }

    private RunState run(RunRegistry reg) {
        return reg.find("getviral-prod", TRACE).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> view, String key) {
        return (List<Map<String, Object>>) view.get(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> view, String key) {
        return (Map<String, Object>) view.get(key);
    }

    @Test
    void aRunStartsRunningAndShowsItsWorkflowAndWhoIsActive() {
        RunRegistry reg = registry(
                log(0, Semconv.EV_RUN_START, "workflow ContentStudio", Semconv.LOOM_RUN_ID, RUN, Semconv.LOOM_WORKFLOW, "ContentStudio"),
                start(1, "s0", Semconv.KIND_AGENT, "invoke_agent Editor", "Editor", null, "ContentStudio/s0"),
                start(2, "t1", Semconv.KIND_TOOL, "execute_tool Wiki", "Editor", "Wiki", "ContentStudio/s0"));
        Map<String, Object> v = run(reg).view();
        assertThat(v.get("status")).isEqualTo("running");
        assertThat(v.get("workflow")).isEqualTo("ContentStudio");
        assertThat(v.get("runId")).isEqualTo(RUN);
        Map<String, Object> editor = list(v, "agents").get(0);
        assertThat(editor).containsEntry("name", "Editor").containsEntry("status", "running").containsEntry("currentStep", "ContentStudio/s0");
        assertThat(editor.get("activeTools")).isEqualTo(List.of("Wiki"));
        assertThat(list(v, "tools").get(0)).containsEntry("name", "Wiki").containsEntry("inFlight", 1L);
        assertThat(list(v, "openSteps")).hasSize(2);
    }

    @Test
    void aFinishedStepAddsItsTokensCostAndCallsToTheAgentAndTheRun_andOnlyOnce() {
        RunRegistry reg = registry(log(0, Semconv.EV_RUN_START, "w", Semconv.LOOM_RUN_ID, RUN, Semconv.LOOM_WORKFLOW, "W"),
                start(1, "s0", Semconv.KIND_AGENT, "invoke_agent Editor", "Editor", null, "W/s0"));
        SpanRec done = agentSpan("s0", "Editor", 1, 20, 5000, 3, "0.27");
        reg.applySpans(PROC, List.of(done, done)); // the same span arrives twice
        Map<String, Object> v = run(reg).view();
        assertThat(map(v, "totals")).containsEntry("tokens", 5000L).containsEntry("calls", 3L).containsEntry("cost", "0.27");
        Map<String, Object> editor = list(v, "agents").get(0);
        assertThat(editor).containsEntry("status", "done").containsEntry("tokens", 5000L);
        assertThat(list(v, "openSteps")).isEmpty();
    }

    @Test
    void toolCallsAreCountedWithTheirErrorsAndAverageTime() {
        RunRegistry reg = registry(log(0, Semconv.EV_RUN_START, "w", Semconv.LOOM_RUN_ID, RUN, Semconv.LOOM_WORKFLOW, "W"));
        reg.applySpans(PROC, List.of(toolSpan("t1", "Competitor", "Example", 1, 3, true), toolSpan("t2", "Competitor", "Example", 4, 6, false)));
        Map<String, Object> tool = list(run(reg).view(), "tools").get(0);
        assertThat(tool).containsEntry("name", "Example").containsEntry("calls", 2L).containsEntry("errors", 1L).containsEntry("avgMillis", 2000L);
    }

    @Test
    void aSleepShowsAsSleepingWithItsWakeTime_andRunsAgainWhenTheSleepSpanEnds() {
        String wake = "2026-10-11T14:03:30Z";
        RunRegistry reg = registry(log(0, Semconv.EV_RUN_START, "w", Semconv.LOOM_RUN_ID, RUN, Semconv.LOOM_WORKFLOW, "W"),
                start(1, "z", Semconv.KIND_SLEEP, "loom.sleep", null, null, "W/s1", Semconv.LOOM_SLEEP_WAKE_AT, wake));
        Map<String, Object> v = run(reg).view();
        assertThat(v.get("status")).isEqualTo("sleeping");
        assertThat(map(v, "sleeping")).containsEntry("wakeAt", wake);

        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.LOOM_SPAN_KIND, Semconv.KIND_SLEEP);
        reg.applySpans(PROC, List.of(new SpanRec(TRACE, Ids.spanId(RUN, "z"), null, "loom.sleep", SpanRec.INTERNAL, at(1), at(91), a, SpanRec.STATUS_OK, null, List.of())));
        assertThat(run(reg).view().get("status")).isEqualTo("running");
    }

    @Test
    void aSuspendedRunShowsWhyAndWhenItResumes_perReason() {
        RunRegistry reg = registry(log(0, Semconv.EV_RUN_START, "w", Semconv.LOOM_RUN_ID, RUN, Semconv.LOOM_WORKFLOW, "W"),
                log(5, Semconv.EV_RUN_SUSPENDED, "paused", Semconv.LOOM_SUSPEND_REASON, "SLEEP", Semconv.LOOM_RESUME_AT, "2026-10-11T14:03:30Z"),
                log(5, Semconv.EV_RUN_END, "ended", Semconv.LOOM_RUN_STATUS, Semconv.STATUS_SUSPENDED));
        Map<String, Object> v = run(reg).view();
        assertThat(v.get("status")).isEqualTo("sleeping");
        assertThat(map(v, "suspended")).containsEntry("reason", "SLEEP").containsEntry("resumeAt", "2026-10-11T14:03:30Z");
        assertThat(list(v, "openSteps")).isEmpty();

        RunRegistry limited = registry(log(0, Semconv.EV_RUN_START, "w", Semconv.LOOM_RUN_ID, RUN, Semconv.LOOM_WORKFLOW, "W"),
                log(5, Semconv.EV_RUN_SUSPENDED, "paused", Semconv.LOOM_SUSPEND_REASON, "RATE_LIMIT", Semconv.LOOM_RESUME_AT, "2026-10-11T14:04:00Z"));
        assertThat(run(limited).view().get("status")).isEqualTo("paused");
        RunRegistry human = registry(log(0, Semconv.EV_RUN_START, "w", Semconv.LOOM_RUN_ID, RUN, Semconv.LOOM_WORKFLOW, "W"),
                log(5, Semconv.EV_RUN_SUSPENDED, "waiting", Semconv.LOOM_SUSPEND_REASON, "HUMAN"));
        assertThat(run(human).view().get("status")).isEqualTo("waiting");
    }

    @Test
    void theEndOfARunSetsItsFinalStatusAndKeepsEarlierStartTimeAcrossAResume() {
        RunRegistry reg = registry(log(0, Semconv.EV_RUN_START, "w", Semconv.LOOM_RUN_ID, RUN, Semconv.LOOM_WORKFLOW, "W"),
                log(10, Semconv.EV_RUN_SUSPENDED, "sleep", Semconv.LOOM_SUSPEND_REASON, "SLEEP", Semconv.LOOM_RESUME_AT, "x"),
                log(10, Semconv.EV_RUN_END, "e", Semconv.LOOM_RUN_STATUS, Semconv.STATUS_SUSPENDED));
        // the same run, resumed by a new process instance whose records start again at seq 1
        Resource second = Resource.of("getviral-prod", "i_2");
        seq = 0;
        reg.applyLogs(second, List.of(log(100, Semconv.EV_RUN_START, "w", Semconv.LOOM_RUN_ID, RUN, Semconv.LOOM_WORKFLOW, "W"),
                log(120, Semconv.EV_RUN_END, "e", Semconv.LOOM_RUN_STATUS, Semconv.STATUS_COMPLETED)));
        Map<String, Object> v = run(reg).view();
        assertThat(v.get("status")).isEqualTo("finished");
        assertThat(v.get("startedAt")).isEqualTo("2026-10-11T14:02:00Z");
        assertThat(v.get("endedAt")).isEqualTo("2026-10-11T14:04:00Z");
        assertThat(v.get("gaps")).isEqualTo(0L);
    }

    @Test
    void failedAndStoppedRunsAreShownAsSuch() {
        for (String[] c : new String[][] {{Semconv.STATUS_FAILED, "failed"}, {Semconv.STATUS_STOPPED, "stopped"}, {Semconv.STATUS_BUDGET, "stopped"}}) {
            seq = 0;
            RunRegistry reg = registry(log(0, Semconv.EV_RUN_START, "w", Semconv.LOOM_RUN_ID, RUN, Semconv.LOOM_WORKFLOW, "W"),
                    log(3, Semconv.EV_RUN_END, "e", Semconv.LOOM_RUN_STATUS, c[0]));
            assertThat(run(reg).view().get("status")).as(c[0]).isEqualTo(c[1]);
        }
    }

    @Test
    void repeatedLogRecordsAreIgnoredAndSkippedOnesCountedAsAGap() {
        LogRec first = log(0, Semconv.EV_RUN_START, "w", Semconv.LOOM_RUN_ID, RUN, Semconv.LOOM_WORKFLOW, "W");
        seq += 3; // records 2, 3 and 4 never arrive
        LogRec later = log(5, Semconv.EV_NOTE, "hello");
        RunRegistry reg = new RunRegistry();
        reg.applyLogs(PROC, List.of(first, first, later, later));
        Map<String, Object> v = run(reg).view();
        assertThat(v.get("duplicates")).isEqualTo(2L);
        assertThat(v.get("gaps")).isEqualTo(3L);
        assertThat(list(v, "recent")).hasSize(2);
    }

    @Test
    void theRecentListIsBounded() {
        RunRegistry reg = new RunRegistry();
        for (int i = 0; i < 300; i++) reg.applyLogs(PROC, List.of(log(i, Semconv.EV_NOTE, "n" + i)));
        List<Map<String, Object>> recent = list(run(reg).view(), "recent");
        assertThat(recent).hasSize(200);
        assertThat(recent.get(199)).containsEntry("text", "n299");
    }

    @Test
    void runsOfDifferentProcessesWithTheSameWorkflowStaySeparate() {
        RunRegistry reg = new RunRegistry();
        reg.applyLogs(Resource.of("getviral-prod", "i_1"), List.of(log(0, Semconv.EV_RUN_START, "w", Semconv.LOOM_RUN_ID, RUN, Semconv.LOOM_WORKFLOW, "W")));
        seq = 0;
        reg.applyLogs(Resource.of("getviral-staging", "i_9"), List.of(log(0, Semconv.EV_RUN_START, "w", Semconv.LOOM_RUN_ID, RUN, Semconv.LOOM_WORKFLOW, "W")));
        assertThat(reg.views()).hasSize(2);
        assertThat(reg.find("getviral-prod", TRACE)).isPresent();
        assertThat(reg.findByRunId("getviral-staging", RUN)).isPresent();
        assertThat(reg.find("nobody", TRACE)).isEmpty();
    }

    @Test
    void summariesAreShortAndNewestFirst_andARunCanBeFoundByItsTraceId() {
        RunRegistry reg = new RunRegistry();
        Resource a = Resource.of("p", "i_1");
        reg.applyLogs(a, List.of(new LogRec(at(1), LogRec.INFO, "w", Map.of(Semconv.EVENT_NAME, Semconv.EV_RUN_START, Semconv.LOOM_SEQ, 1L, Semconv.LOOM_RUN_ID, "old", Semconv.LOOM_WORKFLOW, "A"), Ids.traceId("old"), null)));
        reg.applyLogs(a, List.of(new LogRec(at(9), LogRec.INFO, "w", Map.of(Semconv.EVENT_NAME, Semconv.EV_RUN_START, Semconv.LOOM_SEQ, 2L, Semconv.LOOM_RUN_ID, "new", Semconv.LOOM_WORKFLOW, "B"), Ids.traceId("new"), null)));
        List<Map<String, Object>> list = reg.summaries();
        assertThat(list).extracting(m -> m.get("runId")).containsExactly("new", "old");
        assertThat(list.get(0)).containsKeys("status", "totals", "runningAgents", "workflow").doesNotContainKeys("recent", "agents", "tools");
        assertThat(reg.findByTrace(Ids.traceId("old")).orElseThrow().runId()).isEqualTo("old");
        assertThat(reg.findByTrace("0".repeat(32))).isEmpty();
    }

    @Test
    void aFinishedToolOrAgentStepShowsInTheLatestEventsWithItsText_andAFailedOneIsMarked() {
        RunRegistry reg = registry(log(0, Semconv.EV_RUN_START, "w", Semconv.LOOM_RUN_ID, RUN, Semconv.LOOM_WORKFLOW, "W"));
        SpanRec withText = new SpanRec(TRACE, Ids.spanId(RUN, "t1"), null, "execute_tool Wiki", SpanRec.CLIENT, at(1), at(2),
                Map.of(Semconv.LOOM_SPAN_KIND, "tool", Semconv.GEN_AI_TOOL_NAME, "Wiki", Semconv.GEN_AI_AGENT_NAME, "Editor", Semconv.LOOM_TEXT, "200 OK"), SpanRec.STATUS_OK, null, List.of());
        SpanRec failedNoText = new SpanRec(TRACE, Ids.spanId(RUN, "t2"), null, "execute_tool Example", SpanRec.CLIENT, at(3), at(4),
                Map.of(Semconv.LOOM_SPAN_KIND, "tool", Semconv.GEN_AI_TOOL_NAME, "Example"), SpanRec.STATUS_ERROR, "404", List.of());
        reg.applySpans(PROC, List.of(withText, failedNoText));
        List<Map<String, Object>> recent = list(run(reg).view(), "recent");
        Map<String, Object> ok = recent.get(recent.size() - 2);
        assertThat(ok).containsEntry("event", "loom.tool.end").containsEntry("text", "200 OK").containsEntry("agent", "Editor");
        Map<String, Object> bad = recent.get(recent.size() - 1);
        assertThat(bad).containsEntry("text", "execute_tool Example failed").containsEntry("failed", true);
    }

    @Test
    void aRunThatHasNotEndedKeepsCountingWhileItSleeps_andAnEndedOneStopsAtItsEnd() {
        RunRegistry reg = registry(log(0, Semconv.EV_RUN_START, "w", Semconv.LOOM_RUN_ID, RUN, Semconv.LOOM_WORKFLOW, "W"),
                log(5, Semconv.EV_RUN_SUSPENDED, "sleep", Semconv.LOOM_SUSPEND_REASON, "SLEEP", Semconv.LOOM_RESUME_AT, "x"));
        Instant fiveMinutesIn = OtlpJson.instant(T0).plusSeconds(300);
        assertThat(run(reg).view(fiveMinutesIn).get("elapsedMillis")).isEqualTo(300_000L);

        seq = 0;
        RunRegistry ended = registry(log(0, Semconv.EV_RUN_START, "w", Semconv.LOOM_RUN_ID, RUN, Semconv.LOOM_WORKFLOW, "W"),
                log(60, Semconv.EV_RUN_END, "e", Semconv.LOOM_RUN_STATUS, Semconv.STATUS_COMPLETED));
        assertThat(run(ended).view(fiveMinutesIn.plusSeconds(3600)).get("elapsedMillis")).isEqualTo(60_000L);
    }
}
