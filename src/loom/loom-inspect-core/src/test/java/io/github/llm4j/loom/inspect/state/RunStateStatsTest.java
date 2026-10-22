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
 * R8, R9: per-agent context, per-model and per-tool statistics, and how the view shows them.
 * Records in, view out.
 */
class RunStateStatsTest {

    private static final String RUN = "r-stats";
    private static final String TRACE = Ids.traceId(RUN);
    private static final Resource PROC = Resource.of("p", "i_1");
    private static final long T0 = OtlpJson.nanos(Instant.parse("2026-10-14T10:00:00Z"));

    private final RunState state = new RunState("p", TRACE);
    private long seq;
    private int spanNo;

    private static long at(double seconds) {
        return T0 + (long) (seconds * 1_000_000_000L);
    }

    private void log(double second, String event, String agent, Object... kv) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.EVENT_NAME, event);
        a.put(Semconv.LOOM_SEQ, ++seq);
        if (agent != null) a.put(Semconv.GEN_AI_AGENT_NAME, agent);
        for (int i = 0; i < kv.length; i += 2) a.put((String) kv[i], kv[i + 1]);
        state.apply("i_1", new LogRec(at(second), LogRec.INFO, event, a, TRACE, null));
    }

    private void chat(double endSecond, String agent, long durationMs, Object... kv) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.LOOM_SPAN_KIND, Semconv.KIND_MODEL);
        a.put(Semconv.GEN_AI_OPERATION_NAME, Semconv.OP_CHAT);
        a.put(Semconv.GEN_AI_AGENT_NAME, agent);
        a.put(Semconv.GEN_AI_RESPONSE_MODEL, "m1");
        a.put(Semconv.GEN_AI_USAGE_INPUT_TOKENS, 100L);
        a.put(Semconv.GEN_AI_USAGE_OUTPUT_TOKENS, 20L);
        a.put(Semconv.LOOM_MODEL_ATTEMPTS, 1L);
        a.put(Semconv.LOOM_MODEL_WAITED_MS, 0L);
        a.put(Semconv.LOOM_MODEL_DURATION_MS, durationMs);
        a.put(Semconv.LOOM_MODEL_TRUNCATED, false);
        a.put(Semconv.LOOM_STEP, "Main/s0");
        boolean failed = false;
        for (int i = 0; i < kv.length; i += 2) {
            a.put((String) kv[i], kv[i + 1]);
            if (Semconv.ERROR_TYPE.equals(kv[i])) failed = true;
        }
        long end = at(endSecond);
        state.apply(
                new SpanRec(
                        TRACE,
                        Ids.spanId(RUN, "model" + (++spanNo)),
                        null,
                        "chat m1",
                        SpanRec.CLIENT,
                        end - durationMs * 1_000_000L,
                        end,
                        a,
                        failed ? SpanRec.STATUS_ERROR : SpanRec.STATUS_OK,
                        null,
                        List.of()));
    }

    private void tool(double endSecond, String agent, String tool, long durationMs, Object... kv) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.LOOM_SPAN_KIND, Semconv.KIND_TOOL);
        a.put(Semconv.GEN_AI_AGENT_NAME, agent);
        a.put(Semconv.GEN_AI_TOOL_NAME, tool);
        a.put(Semconv.LOOM_STEP, "Main/s0");
        boolean failed = false;
        for (int i = 0; i < kv.length; i += 2) {
            a.put((String) kv[i], kv[i + 1]);
            if (Semconv.ERROR_TYPE.equals(kv[i])) failed = true;
        }
        long end = at(endSecond);
        state.apply(
                new SpanRec(
                        TRACE,
                        Ids.spanId(RUN, "tool" + (++spanNo)),
                        null,
                        "execute_tool " + tool,
                        SpanRec.CLIENT,
                        end - durationMs * 1_000_000L,
                        end,
                        a,
                        failed ? SpanRec.STATUS_ERROR : SpanRec.STATUS_OK,
                        null,
                        List.of()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> model(String name) {
        return ((List<Map<String, Object>>) state.view().get("models"))
                .stream().filter(m -> name.equals(m.get("name"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toolRow(String name) {
        return ((List<Map<String, Object>>) state.view().get("tools"))
                .stream().filter(m -> name.equals(m.get("name"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> agentRow(String name) {
        return ((List<Map<String, Object>>) state.view().get("agents"))
                .stream().filter(m -> name.equals(m.get("name"))).findFirst().orElseThrow();
    }

    // ── models ──────────────────────────────────────────────────────────────────

    @Test
    void modelsCountCallsTokensAndLatency() {
        for (int i = 1; i <= 10; i++) chat(i, "Editor", i * 100L);
        Map<String, Object> m = model("m1");
        assertThat(m.get("calls")).isEqualTo(10L);
        assertThat(m.get("promptTokens")).isEqualTo(1000L);
        assertThat(m.get("completionTokens")).isEqualTo(200L);
        assertThat(m.get("p50Ms")).isEqualTo(500L);
        assertThat(m.get("p95Ms")).isEqualTo(1000L);
        assertThat(m.get("failures")).isEqualTo(Map.of());
    }

    @Test
    void failuresAreGroupedByCategoryAndRetriesAndFailoversAreAdded() {
        chat(
                1,
                "Editor",
                100,
                Semconv.ERROR_TYPE,
                "rate_limited",
                Semconv.LOOM_MODEL_ATTEMPTS,
                3L,
                Semconv.LOOM_MODEL_WAITED_MS,
                40L);
        chat(2, "Editor", 100, Semconv.ERROR_TYPE, "rate_limited");
        chat(
                3,
                "Editor",
                100,
                Semconv.ERROR_TYPE,
                "timeout",
                Semconv.LOOM_MODEL_FAILOVERS,
                "Primary,Backup");
        chat(4, "Editor", 100, Semconv.LOOM_MODEL_TRUNCATED, true);
        Map<String, Object> m = model("m1");
        assertThat(m.get("calls")).isEqualTo(4L);
        assertThat(m.get("failures")).isEqualTo(Map.of("rate_limited", 2L, "timeout", 1L));
        assertThat(m.get("retries")).isEqualTo(2L);
        assertThat(m.get("waitedMs")).isEqualTo(40L);
        assertThat(m.get("failovers")).isEqualTo(2L);
        assertThat(m.get("truncated")).isEqualTo(1L);
    }

    @Test
    void aFailedCallWithNoModelNameIsFiledUnderUnknown() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.LOOM_SPAN_KIND, Semconv.KIND_MODEL);
        a.put(Semconv.GEN_AI_AGENT_NAME, "Editor");
        a.put(Semconv.ERROR_TYPE, "authentication");
        a.put(Semconv.LOOM_STEP, "Main/s0");
        state.apply(
                new SpanRec(
                        TRACE,
                        Ids.spanId(RUN, "nomodel"),
                        null,
                        "chat model",
                        SpanRec.CLIENT,
                        at(1),
                        at(2),
                        a,
                        SpanRec.STATUS_ERROR,
                        null,
                        List.of()));
        assertThat(model("unknown").get("calls")).isEqualTo(1L);
    }

    @Test
    void aModelSpanDoesNotChangeStepsOrTheRecentFeedOrAgentTotals() {
        Map<String, Object> before = state.view();
        chat(1, "Editor", 100);
        Map<String, Object> after = state.view();
        assertThat(after.get("steps")).isEqualTo(before.get("steps"));
        assertThat(after.get("recent")).isEqualTo(before.get("recent"));
        assertThat(((Map<?, ?>) after.get("totals")).get("tokens"))
                .isEqualTo(((Map<?, ?>) before.get("totals")).get("tokens"));
    }

    // ── tools ───────────────────────────────────────────────────────────────────

    @Test
    void toolsGetASuccessRateSoftFailuresCategoriesAndPercentiles() {
        for (int i = 1; i <= 7; i++)
            tool(
                    i,
                    "Editor",
                    "Wiki",
                    i * 10L,
                    Semconv.LOOM_TOOL_DURATION_MS,
                    i * 10L,
                    Semconv.LOOM_TOOL_SOFT_FAILURE,
                    false);
        tool(
                8,
                "Editor",
                "Wiki",
                80,
                Semconv.ERROR_TYPE,
                "not_found",
                Semconv.LOOM_TOOL_SOFT_FAILURE,
                true,
                Semconv.LOOM_TOOL_DURATION_MS,
                80L);
        tool(
                9,
                "Editor",
                "Wiki",
                90,
                Semconv.ERROR_TYPE,
                "timeout",
                Semconv.LOOM_TOOL_SOFT_FAILURE,
                false,
                Semconv.LOOM_TOOL_DURATION_MS,
                90L);
        tool(
                10,
                "Editor",
                "Wiki",
                100,
                Semconv.ERROR_TYPE,
                "not_found",
                Semconv.LOOM_TOOL_SOFT_FAILURE,
                true,
                Semconv.LOOM_TOOL_DURATION_MS,
                100L);
        Map<String, Object> t = toolRow("Wiki");
        assertThat(t.get("calls")).isEqualTo(10L);
        assertThat(t.get("errors")).isEqualTo(3L);
        assertThat(t.get("successes")).isEqualTo(7L);
        assertThat((double) t.get("successRate")).isEqualTo(0.7);
        assertThat(t.get("softFailures")).isEqualTo(2L);
        assertThat(t.get("failures")).isEqualTo(Map.of("not_found", 2L, "timeout", 1L));
        assertThat(t.get("p50Ms")).isEqualTo(50L);
        assertThat(t.get("p95Ms")).isEqualTo(100L);
        assertThat(t).containsKeys("avgMillis", "inFlight");
    }

    @Test
    void anErrorWithoutACategoryIsOther_andACallThatNeverRanIsNotCounted() {
        tool(1, "Editor", "Wiki", 10, Semconv.ERROR_TYPE, "tool_error");
        tool(2, "Editor", "Wiki", 10, Semconv.LOOM_TOOL_NO_RESULT, true);
        Map<String, Object> t = toolRow("Wiki");
        assertThat(t.get("failures")).isEqualTo(Map.of("tool_error", 1L));
        assertThat(t.get("calls")).isEqualTo(2L);
        assertThat(t.get("successes")).isEqualTo(0L);
        assertThat((double) t.get("successRate")).isEqualTo(0.0);
    }

    @Test
    void aToolWithNoCallsHasNoRate() {
        tool(1, "Editor", "Wiki", 10, Semconv.LOOM_TOOL_NO_RESULT, true);
        assertThat(toolRow("Wiki").get("successRate")).isNull();
    }

    // ── context ─────────────────────────────────────────────────────────────────

    @Test
    void anAgentsContextIsTheLatestUseWithItsSeries() {
        for (int i = 1; i <= 5; i++) {
            log(
                    i,
                    Semconv.EV_CONTEXT,
                    "Editor",
                    Semconv.LOOM_CONTEXT_USED,
                    400L * i,
                    Semconv.LOOM_CONTEXT_WINDOW,
                    3000L,
                    Semconv.LOOM_CONTEXT_USABLE,
                    2500L,
                    Semconv.LOOM_CONTEXT_FRACTION,
                    400.0 * i / 2500,
                    Semconv.LOOM_CONTEXT_SOURCE,
                    "REPORTED");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> c = (Map<String, Object>) agentRow("Editor").get("context");
        assertThat(c.get("used")).isEqualTo(2000L);
        assertThat(c.get("window")).isEqualTo(3000L);
        assertThat(c.get("usable")).isEqualTo(2500L);
        assertThat((double) c.get("fraction")).isEqualTo(0.8);
        assertThat(c.get("source")).isEqualTo("REPORTED");
        assertThat((List<?>) c.get("series")).hasSize(5);
        assertThat(c.get("compactions")).isEqualTo(0L);
    }

    @Test
    void theSeriesKeepsTheLastSixtySamples() {
        for (int i = 1; i <= 100; i++) {
            log(
                    i,
                    Semconv.EV_CONTEXT,
                    "Editor",
                    Semconv.LOOM_CONTEXT_USED,
                    (long) i,
                    Semconv.LOOM_CONTEXT_USABLE,
                    1000L,
                    Semconv.LOOM_CONTEXT_FRACTION,
                    i / 1000.0,
                    Semconv.LOOM_CONTEXT_SOURCE,
                    "ESTIMATED");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> c = (Map<String, Object>) agentRow("Editor").get("context");
        List<?> series = (List<?>) c.get("series");
        assertThat(series).hasSize(60);
        assertThat((double) series.get(59)).isEqualTo(0.1);
    }

    @Test
    void contextWithoutAWindowHasNoFraction() {
        log(
                1,
                Semconv.EV_CONTEXT,
                "Editor",
                Semconv.LOOM_CONTEXT_USED,
                900L,
                Semconv.LOOM_CONTEXT_SOURCE,
                "ESTIMATED");
        @SuppressWarnings("unchecked")
        Map<String, Object> c = (Map<String, Object>) agentRow("Editor").get("context");
        assertThat(c.get("used")).isEqualTo(900L);
        assertThat(c).doesNotContainKeys("window", "fraction", "series");
    }

    @Test
    void compactionsAreCountedAndTheLastOneIsKept() {
        log(
                1,
                Semconv.EV_COMPACTION,
                "Editor",
                Semconv.LOOM_COMPACTION_STRATEGY,
                "drop_oldest",
                Semconv.LOOM_COMPACTION_OUTCOME,
                "COMPACTED",
                Semconv.LOOM_COMPACTION_BEFORE,
                2100L,
                Semconv.LOOM_COMPACTION_AFTER,
                900L);
        log(
                2,
                Semconv.EV_COMPACTION,
                "Editor",
                Semconv.LOOM_COMPACTION_STRATEGY,
                "drop_oldest",
                Semconv.LOOM_COMPACTION_OUTCOME,
                "NO_GAIN",
                Semconv.LOOM_COMPACTION_BEFORE,
                2000L,
                Semconv.LOOM_COMPACTION_AFTER,
                2000L);
        log(
                3,
                Semconv.EV_COMPACTION,
                "Editor",
                Semconv.LOOM_COMPACTION_STRATEGY,
                "summarize",
                Semconv.LOOM_COMPACTION_OUTCOME,
                "COMPACTED",
                Semconv.LOOM_COMPACTION_BEFORE,
                2300L,
                Semconv.LOOM_COMPACTION_AFTER,
                800L);
        @SuppressWarnings("unchecked")
        Map<String, Object> c = (Map<String, Object>) agentRow("Editor").get("context");
        assertThat(c.get("compactions")).isEqualTo(2L);
        assertThat(c.get("attempts")).isEqualTo(3L);
        @SuppressWarnings("unchecked")
        Map<String, Object> last = (Map<String, Object>) c.get("lastCompaction");
        assertThat(last.get("strategy")).isEqualTo("summarize");
        assertThat(last.get("before")).isEqualTo(2300L);
        assertThat(last.get("after")).isEqualTo(800L);
        assertThat(last.get("outcome")).isEqualTo("COMPACTED");
    }

    @Test
    void summaryCarriesTheHighestContextFraction() {
        log(
                1,
                Semconv.EV_CONTEXT,
                "A",
                Semconv.LOOM_CONTEXT_USED,
                100L,
                Semconv.LOOM_CONTEXT_USABLE,
                1000L,
                Semconv.LOOM_CONTEXT_FRACTION,
                0.1,
                Semconv.LOOM_CONTEXT_SOURCE,
                "REPORTED");
        log(
                2,
                Semconv.EV_CONTEXT,
                "B",
                Semconv.LOOM_CONTEXT_USED,
                900L,
                Semconv.LOOM_CONTEXT_USABLE,
                1000L,
                Semconv.LOOM_CONTEXT_FRACTION,
                0.9,
                Semconv.LOOM_CONTEXT_SOURCE,
                "REPORTED");
        assertThat((double) state.summary().get("contextFraction")).isEqualTo(0.9);
    }

    @Test
    void noContextNoSummaryField() {
        assertThat(state.summary()).doesNotContainKey("contextFraction");
    }

    // ── what must not change ────────────────────────────────────────────────────

    @Test
    void contextLogsDoNotFloodTheRecentFeed() {
        for (int i = 1; i <= 50; i++)
            log(
                    i,
                    Semconv.EV_CONTEXT,
                    "Editor",
                    Semconv.LOOM_CONTEXT_USED,
                    (long) i,
                    Semconv.LOOM_CONTEXT_SOURCE,
                    "ESTIMATED");
        assertThat((List<?>) state.view().get("recent")).isEmpty();
    }

    @Test
    void oldFieldsOfTheViewAreAllStillThere() {
        tool(1, "Editor", "Wiki", 10);
        Map<String, Object> v = state.view();
        assertThat(v)
                .containsKeys(
                        "client",
                        "traceId",
                        "runId",
                        "workflow",
                        "status",
                        "totals",
                        "agents",
                        "tools",
                        "openSteps",
                        "ladders",
                        "steps",
                        "spend",
                        "gaps",
                        "incidents",
                        "control",
                        "duplicates",
                        "recent");
        assertThat(v).containsKey("models");
    }

    @Test
    void statisticsAreBoundedAndTotalsStayExactThroughASoak() {
        for (int i = 1; i <= 20_000; i++) {
            chat(1 + i / 1000.0, "Editor", i % 500);
            tool(
                    1 + i / 1000.0,
                    "Editor",
                    "Wiki",
                    i % 300,
                    Semconv.LOOM_TOOL_DURATION_MS,
                    (long) (i % 300));
        }
        assertThat(model("m1").get("calls")).isEqualTo(20_000L);
        assertThat(toolRow("Wiki").get("calls")).isEqualTo(20_000L);
        assertThat((long) model("m1").get("p95Ms")).isBetween(400L, 499L);
    }
}
