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
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** R29 to R31, R33: a run keeps the records it was built from (bounded) and can say how it looked at any point of them. */
class RunHistoryTest {

    static final long T0 = OtlpJson.nanos(Instant.parse("2026-10-14T10:00:00Z"));
    static final String RUN = "history-run";
    static final String TRACE = Ids.traceId(RUN);

    private long n;

    private LogRec start() {
        return new LogRec(T0, LogRec.INFO, "start", Map.of(Semconv.EVENT_NAME, Semconv.EV_RUN_START, Semconv.LOOM_SEQ, ++n, Semconv.LOOM_RUN_ID, RUN, Semconv.LOOM_WORKFLOW, "Main"), TRACE, null);
    }

    private SpanRec tool(int i, String category) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.LOOM_SPAN_KIND, Semconv.KIND_TOOL);
        a.put(Semconv.GEN_AI_AGENT_NAME, "Writer");
        a.put(Semconv.GEN_AI_TOOL_NAME, "Search");
        a.put(Semconv.LOOM_STEP, "Main/s0");
        if (category != null) a.put(Semconv.ERROR_TYPE, category);
        long end = T0 + (long) (i + 1) * 1_000_000_000L;
        return new SpanRec(TRACE, Ids.spanId(RUN, "s" + i), null, "Search", SpanRec.CLIENT, end - 100_000_000L, end, a, category == null ? SpanRec.STATUS_OK : SpanRec.STATUS_ERROR, null, List.of());
    }

    private void feed(RunState s, int calls, int failFrom) {
        s.apply("i_1", start());
        for (int i = 0; i < calls; i++) s.apply(tool(i, i >= failFrom ? "timeout" : null));
    }

    private static Map<String, Object> plain(Map<String, Object> view) {
        Map<String, Object> m = new LinkedHashMap<>(view);
        for (String k : List.of("history", "live", "asOf", "seq")) m.remove(k);
        return m;
    }

    @Test
    void everyRecordIsKeptInTheOrderItArrived() {
        RunState s = new RunState("shop", TRACE);
        assertThat(s.lastSeq()).isEqualTo(-1);
        feed(s, 9, 99);
        assertThat(s.historySize()).isEqualTo(10);
        assertThat(s.firstSeq()).isZero();
        assertThat(s.lastSeq()).isEqualTo(9);
        @SuppressWarnings("unchecked")
        Map<String, Object> h = (Map<String, Object>) s.view().get("history");
        assertThat(h.get("firstSeq")).isEqualTo(0L);
        assertThat(h.get("lastSeq")).isEqualTo(9L);
        assertThat(h.get("from")).isNotNull();
    }

    @Test
    void theOldestRecordsDropWhenTheBoundIsPassedAndTheHistoryNowStartsLater() {
        RunState s = new RunState("shop", TRACE, 5);
        feed(s, 11, 99); // 12 records
        assertThat(s.historySize()).isEqualTo(5);
        assertThat(s.firstSeq()).isEqualTo(7);
        assertThat(s.lastSeq()).isEqualTo(11);
        assertThat(s.viewAt(3)).as("before the history").isEmpty();
        assertThat(s.viewAt(7)).isPresent();
        assertThat(s.viewAt(7).orElseThrow().get("partial")).as("the totals miss what was dropped").isEqualTo(true);
        assertThat(new RunState("shop", TRACE).viewAt(0)).isEmpty();
        assertThat(s.viewAt(12)).as("after the end").isEmpty();
        assertThat(s.historyStartsAt()).isNotNull();
    }

    @Test
    void theViewAtAPointIsTheViewOfAnythingFedUpToThatPoint() {
        RunState whole = new RunState("shop", TRACE);
        feed(whole, 12, 6); // calls 6..11 fail: an incident opens along the way
        for (int k : new int[] {0, 1, 4, 8, 12}) {
            Optional<Map<String, Object>> at = whole.viewAt(k);
            assertThat(at).as("seq " + k).isPresent();
            RunState prefix = new RunState("shop", TRACE);
            prefix.apply("i_1", start());
            for (int i = 0; i < k; i++) prefix.apply(tool(i, i >= 6 ? "timeout" : null));
            Instant asOf = Instant.parse(String.valueOf(at.get().get("asOf")));
            assertThat(plain(at.get())).as("the view at " + k).isEqualTo(plain(prefix.view(asOf)));
            assertThat(at.get().get("live")).isEqualTo(false);
            assertThat(at.get().get("seq")).isEqualTo((long) k);
        }
    }

    @Test
    void theViewAtTheLastPointIsTheLiveViewOfAFinishedRun() {
        RunState s = new RunState("shop", TRACE);
        feed(s, 8, 5);
        long last = s.lastSeq();
        Map<String, Object> at = s.viewAt(last).orElseThrow();
        Instant asOf = Instant.parse(String.valueOf(at.get("asOf")));
        assertThat(plain(at)).isEqualTo(plain(s.view(asOf)));
    }

    @Test
    void whatTheServerKnowsAboutARunIsInItsPastToo() {
        RunState s = new RunState("shop", TRACE);
        feed(s, 6, 99);
        PriceBook prices = PriceBook.parse("m = 1 / 2").book();
        s.setPrices(prices);
        s.setCentralRules(Map.of("Writer", new InspectRules(2, null, null, null, null, null, null, null, null, null)));
        Map<String, Object> at = s.viewAt(s.lastSeq()).orElseThrow();
        Instant asOf = Instant.parse(String.valueOf(at.get("asOf")));
        assertThat(plain(at)).isEqualTo(plain(s.view(asOf)));
    }

    @Test
    void aLongRunStaysWithinTheBound() {
        RunState s = new RunState("shop", TRACE);
        s.apply("i_1", start());
        for (int i = 0; i < 60_000; i++) s.apply(tool(i, i % 7 == 0 ? "timeout" : null));
        assertThat(s.historySize()).isEqualTo(RunState.HISTORY_MAX);
        assertThat(s.firstSeq()).isEqualTo(60_001L - RunState.HISTORY_MAX);
        assertThat(s.viewAt(s.lastSeq())).isPresent();
        assertThat(s.timeline().size()).isLessThanOrEqualTo(300);
    }

    @Test
    void theTimelineMarksTheStartAndEachIncidentInOrder() {
        RunState s = new RunState("shop", TRACE);
        feed(s, 10, 4); // six failures in a row open an incident
        List<Map<String, Object>> marks = s.timeline();
        assertThat(marks.get(0).get("kind")).isEqualTo("start");
        assertThat(marks).anyMatch(m -> "incident-open".equals(m.get("kind")));
        List<Long> seqs = new ArrayList<>();
        marks.forEach(m -> seqs.add((Long) m.get("seq")));
        assertThat(seqs).isSorted();
        marks.forEach(m -> assertThat(m).containsKeys("seq", "at", "kind", "label"));
        long open = marks.stream().filter(m -> "incident-open".equals(m.get("kind"))).mapToLong(m -> (Long) m.get("seq")).findFirst().orElseThrow();
        // the view at the mark already has the incident; the view just before does not
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> at = (List<Map<String, Object>>) s.viewAt(open).orElseThrow().get("incidents");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> before = (List<Map<String, Object>>) s.viewAt(open - 1).orElseThrow().get("incidents");
        assertThat(at).isNotEmpty();
        assertThat(before).isEmpty();
    }
}
