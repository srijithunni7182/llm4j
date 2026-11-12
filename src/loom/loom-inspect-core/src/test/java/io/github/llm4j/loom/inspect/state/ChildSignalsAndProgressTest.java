package io.github.llm4j.loom.inspect.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.llm4j.loom.inspect.otel.Ids;
import io.github.llm4j.loom.inspect.otel.LogRec;
import io.github.llm4j.loom.inspect.otel.OtlpJson;
import io.github.llm4j.loom.inspect.otel.Resource;
import io.github.llm4j.loom.inspect.otel.Semconv;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Phase 34e (D16, F10): a parent's Children section shows how many signals each child has not read
 * and what it last reported, as additive attributes. And the compatibility test phase 33 left
 * undone: a console that knows these records reads a process that never sends them, and a console
 * that does not know them ignores them, so an older console against a newer process, and the
 * reverse, both keep working.
 */
class ChildSignalsAndProgressTest {

    static final long T0 = OtlpJson.nanos(Instant.parse("2026-10-14T10:00:00Z"));
    private long seq;

    private Map<String, Object> base(String event, String run) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.EVENT_NAME, event);
        a.put(Semconv.LOOM_SEQ, ++seq);
        a.put(Semconv.LOOM_RUN_ID, run);
        return a;
    }

    private LogRec start(String run, String parent) {
        Map<String, Object> a = base(Semconv.EV_RUN_START, run);
        a.put(Semconv.LOOM_WORKFLOW, parent == null ? "Main" : "Research");
        if (parent != null) a.put(Semconv.LOOM_PARENT_RUN_ID, parent);
        return new LogRec(T0 + seq, LogRec.INFO, "start", a, Ids.traceId(run), null);
    }

    private LogRec signal(String run, String op, String peer, String name, boolean sticky) {
        Map<String, Object> a = base(Semconv.EV_SIGNAL, run);
        a.put("loom.data.op", op);
        a.put("loom.data.peer", peer);
        a.put("loom.data.name", name);
        a.put("loom.data.sticky", sticky);
        return new LogRec(T0 + seq, LogRec.INFO, "signal", a, Ids.traceId(run), null);
    }

    private LogRec progress(String run, String field, Object value) {
        Map<String, Object> a = base(Semconv.EV_PROGRESS, run);
        a.put("loom.data.f." + field, value);
        return new LogRec(T0 + seq, LogRec.INFO, "progress", a, Ids.traceId(run), null);
    }

    private RunRegistry registry(LogRec... records) {
        RunRegistry registry = new RunRegistry();
        registry.applyLogs(Resource.of("local", "i_1"), List.of(records));
        return registry;
    }

    private Map<String, Object> childRow(RunRegistry registry) {
        return registry.childrenOf(registry.findByRunId("local", "p").orElseThrow()).get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @Test
    void aParentSeesHowManySignalsItSentThatTheChildHasNotRead() {
        var r =
                registry(
                        start("p", null), start("c", "p"),
                        signal("p", "sent", "c", "go", false), signal("p", "sent", "c", "go", false), signal("p", "sent", "c", "go", false),
                        signal("c", "read", "p", "go", false));

        assertThat(map(childRow(r).get("signals"))).containsEntry("toChild", 2).containsEntry("toParent", 0);
    }

    @Test
    void aParentSeesHowManySignalsTheChildSentThatItHasNotRead() {
        var r =
                registry(
                        start("p", null), start("c", "p"),
                        signal("c", "sent", "p", "found", false), signal("c", "sent", "p", "found", false),
                        signal("p", "read", "c", "found", false));

        assertThat(map(childRow(r).get("signals"))).containsEntry("toParent", 1).containsEntry("toChild", 0);
    }

    @Test
    void aLatchIsNotCountedAsPendingBecauseReadingItConsumesNothing() {
        var r = registry(start("p", null), start("c", "p"), signal("p", "sent", "c", "approved", true), signal("c", "read", "p", "approved", true));

        assertThat(map(childRow(r).get("signals"))).containsEntry("toChild", 0);
    }

    @Test
    void thePendingCountNeverGoesBelowZero() {
        var r = registry(start("p", null), start("c", "p"), signal("c", "read", "p", "go", false), signal("c", "read", "p", "go", false));

        assertThat(map(childRow(r).get("signals"))).containsEntry("toChild", 0);
    }

    @Test
    void aChildsLatestReportIsInItsRowAndEachFieldIsReplacedByTheNewestValue() {
        var r =
                registry(
                        start("p", null), start("c", "p"),
                        progress("c", "stage", "reading"), progress("c", "done", 1),
                        progress("c", "stage", "writing"));

        assertThat(map(childRow(r).get("progress"))).containsEntry("stage", "writing").containsEntry("done", 1);
    }

    @Test
    void aChildThatNeverSignalledOrReportedHasNeitherAttribute() {
        var r = registry(start("p", null), start("c", "p"));

        assertThat(childRow(r)).doesNotContainKey("progress");
        assertThat(map(childRow(r).get("signals"))).containsEntry("toChild", 0).containsEntry("toParent", 0);
    }

    // ---- F10: an old console against a new process, and a new console against an old one ----

    @Test
    void aProcessThatNeverSendsTheNewRecordsIsReadByTheNewConsoleAsBefore() {
        var r = registry(start("p", null), start("c", "p"));

        assertThat(childRow(r)).containsKeys("runId", "status", "totals").doesNotContainKey("progress");
        assertThat(r.summaries()).allSatisfy(s -> assertThat(s).containsKeys("runId", "status", "totals"));
    }

    @Test
    void aRecordWithAnEventNameThisConsoleDoesNotKnowAndExtraAttributesIsIgnoredNotAnError() {
        LogRec future = new LogRec(T0 + 99, LogRec.INFO, "future", Map.of(Semconv.EVENT_NAME, "loom.something_from_a_newer_process", Semconv.LOOM_RUN_ID, "c", "loom.data.surprise", "x"), Ids.traceId("c"), null);
        LogRec oddSignal = new LogRec(T0 + 100, LogRec.INFO, "signal", Map.of(Semconv.EVENT_NAME, Semconv.EV_SIGNAL, Semconv.LOOM_RUN_ID, "c"), Ids.traceId("c"), null);

        assertThatCode(() -> registry(start("p", null), start("c", "p"), future, oddSignal)).doesNotThrowAnyException();
        var r = registry(start("p", null), start("c", "p"), future, oddSignal);
        assertThat(childRow(r)).containsKeys("runId", "status");
    }

    @Test
    void theSummaryOfARunThatReportedStaysAnOrdinaryJsonMapAnOlderPageCanIgnore() {
        var r = registry(start("p", null), start("c", "p"), progress("c", "stage", "reading"));
        var child = r.summaries().stream().filter(s -> "c".equals(s.get("runId"))).findFirst().orElseThrow();

        assertThat(child.keySet()).contains("runId", "workflow", "status", "totals", "parent", "progress");
        assertThat(child.values()).allSatisfy(v -> assertThat(v).isNotNull());
    }
}
