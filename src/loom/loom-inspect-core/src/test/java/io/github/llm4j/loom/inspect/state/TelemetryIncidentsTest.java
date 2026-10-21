package io.github.llm4j.loom.inspect.state;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.inspect.otel.Ids;
import io.github.llm4j.loom.inspect.otel.LogRec;
import io.github.llm4j.loom.inspect.otel.OtlpJson;
import io.github.llm4j.loom.inspect.otel.Semconv;
import io.github.llm4j.loom.inspect.otel.SpanRec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * R10, R11: context nearly full, truncated answer, compaction thrash, tool success rate and latency
 * spike. Records in, incidents out.
 */
class TelemetryIncidentsTest {

    private static final String RUN = "r-inc";
    private static final String TRACE = Ids.traceId(RUN);
    private static final long T0 = OtlpJson.nanos(Instant.parse("2026-10-14T10:00:00Z"));

    private RunState state = new RunState("p", TRACE);
    private long seq;
    private int spanNo;

    private static long at(double seconds) {
        return T0 + (long) (seconds * 1_000_000_000L);
    }

    private void context(double sec, String agent, double fraction) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.EVENT_NAME, Semconv.EV_CONTEXT);
        a.put(Semconv.LOOM_SEQ, ++seq);
        a.put(Semconv.GEN_AI_AGENT_NAME, agent);
        a.put(Semconv.LOOM_CONTEXT_USED, (long) (fraction * 1000));
        a.put(Semconv.LOOM_CONTEXT_WINDOW, 1200L);
        a.put(Semconv.LOOM_CONTEXT_USABLE, 1000L);
        a.put(Semconv.LOOM_CONTEXT_FRACTION, fraction);
        a.put(Semconv.LOOM_CONTEXT_SOURCE, "REPORTED");
        state.apply("i_1", new LogRec(at(sec), LogRec.INFO, "c", a, TRACE, null));
    }

    private void compaction(double sec, String agent, String outcome, long before, long after) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.EVENT_NAME, Semconv.EV_COMPACTION);
        a.put(Semconv.LOOM_SEQ, ++seq);
        a.put(Semconv.GEN_AI_AGENT_NAME, agent);
        a.put(Semconv.LOOM_COMPACTION_STRATEGY, "drop_oldest");
        a.put(Semconv.LOOM_COMPACTION_OUTCOME, outcome);
        a.put(Semconv.LOOM_COMPACTION_BEFORE, before);
        a.put(Semconv.LOOM_COMPACTION_AFTER, after);
        state.apply("i_1", new LogRec(at(sec), LogRec.INFO, "c", a, TRACE, null));
    }

    private void chat(double endSec, String agent, long ms, Object... kv) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.LOOM_SPAN_KIND, Semconv.KIND_MODEL);
        a.put(Semconv.GEN_AI_AGENT_NAME, agent);
        a.put(Semconv.GEN_AI_RESPONSE_MODEL, "m1");
        a.put(Semconv.LOOM_MODEL_DURATION_MS, ms);
        a.put(Semconv.LOOM_MODEL_TRUNCATED, false);
        a.put(Semconv.LOOM_STEP, "Main/s0");
        for (int i = 0; i < kv.length; i += 2) a.put((String) kv[i], kv[i + 1]);
        long end = at(endSec);
        state.apply(
                new SpanRec(
                        TRACE,
                        Ids.spanId(RUN, "m" + (++spanNo)),
                        null,
                        "chat m1",
                        SpanRec.CLIENT,
                        end - ms * 1_000_000L,
                        end,
                        a,
                        SpanRec.STATUS_OK,
                        null,
                        List.of()));
    }

    private void tool(
            double endSec, String agent, String tool, long ms, boolean ok, String category) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.LOOM_SPAN_KIND, Semconv.KIND_TOOL);
        a.put(Semconv.GEN_AI_AGENT_NAME, agent);
        a.put(Semconv.GEN_AI_TOOL_NAME, tool);
        a.put(Semconv.LOOM_TOOL_DURATION_MS, ms);
        a.put(Semconv.LOOM_STEP, "Main/s0");
        if (!ok) a.put(Semconv.ERROR_TYPE, category);
        long end = at(endSec);
        state.apply(
                new SpanRec(
                        TRACE,
                        Ids.spanId(RUN, "t" + (++spanNo)),
                        null,
                        "execute_tool " + tool,
                        SpanRec.CLIENT,
                        end - ms * 1_000_000L,
                        end,
                        a,
                        ok ? SpanRec.STATUS_OK : SpanRec.STATUS_ERROR,
                        null,
                        List.of()));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> incidents(String kind) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : (List<?>) state.view().get("incidents")) {
            Map<String, Object> m = (Map<String, Object>) o;
            if (kind.equals(m.get("kind"))) out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> open(String kind) {
        return incidents(kind).stream().filter(m -> "open".equals(m.get("state"))).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> evidence(Map<String, Object> incident) {
        return ((List<Map<String, Object>>) incident.get("evidence")).get(0);
    }

    private static InspectRules rules(InspectRules.Telemetry t) {
        return new InspectRules(null, null, null, null, null, null, null, null, t);
    }

    // ── context nearly full ─────────────────────────────────────────────────────

    @Test
    void contextBelowTheWarnThresholdRaisesNothing() {
        context(1, "Editor", 0.84);
        assertThat(incidents("CONTEXT")).isEmpty();
    }

    @Test
    void contextAtTheWarnThresholdWarns_andAtTheHighThresholdEscalates_andClosesWhenItFalls() {
        context(1, "Editor", 0.85);
        assertThat(open("CONTEXT")).hasSize(1);
        assertThat(open("CONTEXT").get(0))
                .containsEntry("severity", "warn")
                .containsEntry("agent", "Editor");
        assertThat(evidence(open("CONTEXT").get(0)))
                .containsEntry("rule", "context_nearly_full")
                .containsEntry("threshold", 0.85);
        context(2, "Editor", 0.96);
        assertThat(open("CONTEXT")).hasSize(1);
        assertThat(open("CONTEXT").get(0)).containsEntry("severity", "high");
        context(3, "Editor", 0.5);
        assertThat(open("CONTEXT")).isEmpty();
        assertThat(incidents("CONTEXT").get(0)).containsEntry("state", "closed");
    }

    @Test
    void theEvidenceNamesTheNumbersAndTheCompactionsSoFar() {
        compaction(0.5, "Editor", "COMPACTED", 2000, 900);
        context(1, "Editor", 0.9);
        String detail = String.valueOf(evidence(open("CONTEXT").get(0)).get("detail"));
        assertThat(detail).contains("900").contains("1000").contains("1 compaction");
    }

    @Test
    void eachAgentHasItsOwnIncident() {
        context(1, "A", 0.9);
        context(1, "B", 0.9);
        context(2, "A", 0.1);
        assertThat(open("CONTEXT"))
                .hasSize(1)
                .first()
                .satisfies(m -> assertThat(m).containsEntry("agent", "B"));
    }

    @Test
    void anAgentsOwnThresholdsApply() {
        state.setRules(
                Map.of(
                        "Editor",
                        rules(
                                new InspectRules.Telemetry(
                                        0.6, 0.8, null, null, null, null, null, null))));
        context(1, "Editor", 0.65);
        context(1, "Other", 0.65);
        assertThat(open("CONTEXT"))
                .hasSize(1)
                .first()
                .satisfies(
                        m ->
                                assertThat(m)
                                        .containsEntry("agent", "Editor")
                                        .containsEntry("severity", "warn"));
        context(2, "Editor", 0.85);
        assertThat(open("CONTEXT").get(0)).containsEntry("severity", "high");
    }

    @Test
    void centralRulesLayerOverTheProcessRules() {
        state.setRules(
                Map.of(
                        "Editor",
                        rules(
                                new InspectRules.Telemetry(
                                        0.6, null, null, null, null, null, null, null))));
        state.setCentralRules(
                Map.of(
                        "Editor",
                        rules(
                                new InspectRules.Telemetry(
                                        0.7, null, null, null, null, null, null, null))));
        context(1, "Editor", 0.65);
        assertThat(open("CONTEXT")).isEmpty();
        context(2, "Editor", 0.75);
        assertThat(open("CONTEXT")).hasSize(1);
    }

    @Test
    void contextWithoutAWindowNeverRaises() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.EVENT_NAME, Semconv.EV_CONTEXT);
        a.put(Semconv.LOOM_SEQ, ++seq);
        a.put(Semconv.GEN_AI_AGENT_NAME, "Editor");
        a.put(Semconv.LOOM_CONTEXT_USED, 999_999L);
        state.apply("i_1", new LogRec(at(1), LogRec.INFO, "c", a, TRACE, null));
        assertThat(incidents("CONTEXT")).isEmpty();
    }

    // ── truncated answers ───────────────────────────────────────────────────────

    @Test
    void aTruncatedAnswerWarns_andThreeOfThemEscalate() {
        chat(1, "Writer", 500, Semconv.LOOM_MODEL_TRUNCATED, false);
        assertThat(incidents("TRUNCATED")).isEmpty();
        chat(
                2,
                "Writer",
                500,
                Semconv.LOOM_MODEL_TRUNCATED,
                true,
                Semconv.GEN_AI_USAGE_OUTPUT_TOKENS,
                4096L,
                Semconv.GEN_AI_RESPONSE_FINISH_REASONS,
                "length");
        assertThat(open("TRUNCATED")).hasSize(1);
        assertThat(open("TRUNCATED").get(0))
                .containsEntry("severity", "warn")
                .containsEntry("agent", "Writer");
        assertThat(String.valueOf(evidence(open("TRUNCATED").get(0)).get("detail")))
                .contains("4096")
                .contains("length");
        chat(3, "Writer", 500, Semconv.LOOM_MODEL_TRUNCATED, true);
        chat(4, "Writer", 500, Semconv.LOOM_MODEL_TRUNCATED, true);
        assertThat(open("TRUNCATED")).hasSize(1);
        assertThat(open("TRUNCATED").get(0)).containsEntry("severity", "high");
        assertThat(evidence(open("TRUNCATED").get(0)).get("measured")).isEqualTo(3L);
    }

    @Test
    void theNumberOfTruncationsThatRaisesItIsConfigurable() {
        state.setRules(
                Map.of(
                        "Writer",
                        rules(
                                new InspectRules.Telemetry(
                                        null, null, 2, null, null, null, null, null))));
        chat(1, "Writer", 500, Semconv.LOOM_MODEL_TRUNCATED, true);
        assertThat(incidents("TRUNCATED")).isEmpty();
        chat(2, "Writer", 500, Semconv.LOOM_MODEL_TRUNCATED, true);
        assertThat(open("TRUNCATED")).hasSize(1);
    }

    // ── compaction thrash ───────────────────────────────────────────────────────

    @Test
    void fourCompactionsWithinTenCallsIsThrash_andItClosesOnceTheyAreSpacedOut() {
        for (int i = 1; i <= 4; i++) {
            chat(i, "Researcher", 100);
            compaction(i + 0.1, "Researcher", "COMPACTED", 2000, 1000);
        }
        assertThat(open("THRASH")).hasSize(1);
        assertThat(open("THRASH").get(0)).containsEntry("severity", "warn");
        assertThat(evidence(open("THRASH").get(0)))
                .containsEntry("rule", "compaction_thrash")
                .containsEntry("measured", 4L)
                .containsEntry("threshold", 4);
        for (int i = 5; i <= 14; i++) chat(i, "Researcher", 100);
        assertThat(open("THRASH")).isEmpty();
    }

    @Test
    void threeCompactionsInARowThatGainedLittleIsThrashToo() {
        compaction(1, "Researcher", "COMPACTED", 1000, 950);
        compaction(2, "Researcher", "NO_GAIN", 1000, 1000);
        assertThat(open("THRASH")).isEmpty();
        compaction(3, "Researcher", "COMPACTED", 1000, 920);
        assertThat(open("THRASH")).hasSize(1);
        assertThat(String.valueOf(evidence(open("THRASH").get(0)).get("detail")))
                .contains("little");
    }

    @Test
    void aGoodCompactionBreaksTheLowGainStreak() {
        compaction(1, "Researcher", "COMPACTED", 1000, 950);
        compaction(2, "Researcher", "COMPACTED", 1000, 400);
        compaction(3, "Researcher", "COMPACTED", 1000, 950);
        assertThat(open("THRASH")).isEmpty();
    }

    @Test
    void thrashThresholdsAreConfigurable() {
        state.setRules(
                Map.of(
                        "Researcher",
                        rules(
                                new InspectRules.Telemetry(
                                        null, null, null, 2, 3, null, null, null))));
        compaction(1, "Researcher", "COMPACTED", 2000, 1000);
        chat(2, "Researcher", 100);
        compaction(3, "Researcher", "COMPACTED", 2000, 1000);
        assertThat(open("THRASH")).hasSize(1);
    }

    // ── tool success rate ───────────────────────────────────────────────────────

    @Test
    void aToolBelowTheRateOverItsLastCallsRaises_andRecoveryCloses() {
        for (int i = 1; i <= 4; i++) tool(i, "Editor", "Wiki", 100, false, "timeout");
        assertThat(incidents("TOOL_RATE")).as("fewer than five calls").isEmpty();
        tool(5, "Editor", "Wiki", 100, true, null);
        assertThat(open("TOOL_RATE")).hasSize(1);
        assertThat(open("TOOL_RATE").get(0)).containsEntry("severity", "high");
        assertThat(evidence(open("TOOL_RATE").get(0))).containsEntry("rule", "tool_success_rate");
        assertThat(String.valueOf(evidence(open("TOOL_RATE").get(0)).get("detail")))
                .contains("timeout")
                .contains("1 of 5");
        for (int i = 6; i <= 15; i++) tool(i, "Editor", "Wiki", 100, true, null);
        assertThat(open("TOOL_RATE")).isEmpty();
    }

    @Test
    void aRateBetweenFortyAndSeventyPercentIsAWarning() {
        for (int i = 1; i <= 5; i++) tool(i, "Editor", "Wiki", 100, i > 2, "not_found");
        assertThat(open("TOOL_RATE")).hasSize(1);
        assertThat(open("TOOL_RATE").get(0)).containsEntry("severity", "warn");
    }

    @Test
    void aHealthyToolRaisesNothing() {
        for (int i = 1; i <= 20; i++) tool(i, "Editor", "Wiki", 100, true, null);
        assertThat(incidents("TOOL_RATE")).isEmpty();
    }

    @Test
    void theRateAndWindowAreConfigurable() {
        state.setRules(
                Map.of(
                        "Editor",
                        rules(
                                new InspectRules.Telemetry(
                                        null, null, null, null, null, 0.9, 5, null))));
        for (int i = 1; i <= 5; i++) tool(i, "Editor", "Wiki", 100, i != 1, "timeout");
        assertThat(open("TOOL_RATE")).hasSize(1);
    }

    @Test
    void aCallThatNeverRanIsNotAFailureOfTheTool() {
        for (int i = 1; i <= 6; i++) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put(Semconv.LOOM_SPAN_KIND, Semconv.KIND_TOOL);
            a.put(Semconv.GEN_AI_AGENT_NAME, "Editor");
            a.put(Semconv.GEN_AI_TOOL_NAME, "Wiki");
            a.put(Semconv.LOOM_TOOL_NO_RESULT, true);
            state.apply(
                    new SpanRec(
                            TRACE,
                            Ids.spanId(RUN, "nr" + i),
                            null,
                            "execute_tool Wiki",
                            SpanRec.CLIENT,
                            at(i),
                            at(i + 0.1),
                            a,
                            SpanRec.STATUS_UNSET,
                            null,
                            List.of()));
        }
        assertThat(incidents("TOOL_RATE")).isEmpty();
    }

    // ── latency spike ───────────────────────────────────────────────────────────

    @Test
    void aCallFarSlowerThanTheRunningMedianIsASpike_andTheNextNormalOneClosesIt() {
        for (int i = 1; i <= 12; i++) chat(i, "Editor", 1000);
        chat(13, "Editor", 6000);
        assertThat(open("SPIKE")).hasSize(1);
        assertThat(open("SPIKE").get(0)).containsEntry("severity", "info");
        assertThat(evidence(open("SPIKE").get(0))).containsEntry("rule", "latency_spike");
        assertThat(String.valueOf(evidence(open("SPIKE").get(0)).get("detail")))
                .contains("6000")
                .contains("1000");
        chat(14, "Editor", 1000);
        assertThat(open("SPIKE")).isEmpty();
    }

    @Test
    void aSecondSpikeInARowIsAWarning() {
        for (int i = 1; i <= 12; i++) chat(i, "Editor", 1000);
        chat(13, "Editor", 6000);
        chat(14, "Editor", 7000);
        assertThat(open("SPIKE")).hasSize(1);
        assertThat(open("SPIKE").get(0)).containsEntry("severity", "warn");
    }

    @Test
    void fewSamplesOrSmallAbsoluteTimesNeverSpike() {
        for (int i = 1; i <= 5; i++) chat(i, "Editor", 100);
        chat(6, "Editor", 5000);
        assertThat(incidents("SPIKE")).as("fewer than ten earlier samples").isEmpty();
        TelemetryIncidentsTest other = new TelemetryIncidentsTest();
        for (int i = 1; i <= 12; i++) other.chat(i, "Editor", 100);
        other.chat(13, "Editor", 1500);
        assertThat(other.incidents("SPIKE")).as("under two seconds").isEmpty();
    }

    @Test
    void toolSpikesAreJudgedPerTool() {
        for (int i = 1; i <= 12; i++) tool(i, "Editor", "Wiki", 1000, true, null);
        for (int i = 1; i <= 12; i++) tool(20 + i, "Editor", "Slow", 5000, true, null);
        tool(40, "Editor", "Slow", 6000, true, null);
        assertThat(incidents("SPIKE")).as("6 s is normal for a tool that takes 5 s").isEmpty();
        tool(41, "Editor", "Wiki", 6000, true, null);
        assertThat(open("SPIKE")).hasSize(1);
    }

    @Test
    void theFactorIsConfigurable() {
        state.setRules(
                Map.of(
                        "Editor",
                        rules(
                                new InspectRules.Telemetry(
                                        null, null, null, null, null, null, null, 2.0))));
        for (int i = 1; i <= 12; i++) chat(i, "Editor", 2000);
        chat(13, "Editor", 4500);
        assertThat(open("SPIKE")).hasSize(1);
    }

    // ── replay and the end of the run ───────────────────────────────────────────

    @Test
    void replayingTheSameRecordsGivesTheSameIncidents() {
        TelemetryIncidentsTest a = new TelemetryIncidentsTest();
        TelemetryIncidentsTest b = new TelemetryIncidentsTest();
        for (TelemetryIncidentsTest t : List.of(a, b)) {
            for (int i = 1; i <= 12; i++) t.chat(i, "Editor", 1000);
            t.chat(13, "Editor", 6000);
            t.context(14, "Editor", 0.97);
            for (int i = 15; i <= 20; i++) t.tool(i, "Editor", "Wiki", 100, false, "timeout");
            t.chat(21, "Editor", 100, Semconv.LOOM_MODEL_TRUNCATED, true);
        }
        assertThat(String.valueOf(a.state.view().get("incidents")).replaceAll("since=[^,}]*", ""))
                .isEqualTo(
                        String.valueOf(b.state.view().get("incidents"))
                                .replaceAll("since=[^,}]*", ""));
        assertThat(a.incidents("SPIKE")).isNotEmpty();
    }

    @Test
    void whenTheRunEndsEverythingOpenIsClosed() {
        context(1, "Editor", 0.97);
        for (int i = 1; i <= 5; i++) tool(i, "Editor", "Wiki", 100, false, "timeout");
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.EVENT_NAME, Semconv.EV_RUN_END);
        a.put(Semconv.LOOM_SEQ, ++seq);
        a.put(Semconv.LOOM_RUN_STATUS, "completed");
        state.apply("i_1", new LogRec(at(30), LogRec.INFO, "end", a, TRACE, null));
        assertThat(open("CONTEXT")).isEmpty();
        assertThat(open("TOOL_RATE")).isEmpty();
    }
}
