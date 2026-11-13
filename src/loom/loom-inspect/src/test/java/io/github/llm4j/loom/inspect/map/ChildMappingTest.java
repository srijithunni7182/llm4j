package io.github.llm4j.loom.inspect.map;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.inspect.otel.LogRec;
import io.github.llm4j.loom.inspect.otel.Semconv;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Phase 33, D16: the start record of a child run carries its parent, so Fabric can link it. */
class ChildMappingTest {

    private static final Instant T0 = Instant.parse("2026-11-11T10:00:00Z");

    private static LogRec start(String... kv) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("workflow", "Research");
        data.put("run", "child-1");
        for (int i = 0; i < kv.length; i += 2) data.put(kv[i], kv[i + 1]);
        TraceMapper.Output o =
                new TraceMapper("child-1", Detail.FULL)
                        .map(new TraceEvent(TraceEvent.RUN_START, null, "Research", "workflow Research", data, T0));
        return o.logs().get(0);
    }

    @Test
    void aChildRunStartNamesItsParent() {
        assertThat(start("parent", "parent-1").attr(Semconv.LOOM_PARENT_RUN_ID)).isEqualTo("parent-1");
    }

    @Test
    void aRunNobodyStartedHasNoParentAttribute() {
        assertThat(start().attr(Semconv.LOOM_PARENT_RUN_ID)).isNull();
    }

    // ---- phase 34e: progress and signals reach the console as records ----

    private static LogRec traced(String run, String type, Map<String, Object> data) {
        return new TraceMapper(run, Detail.FULL).map(new TraceEvent(type, null, type, type, data, T0)).logs().get(0);
    }

    @Test
    void aProgressReportBecomesARecordWithEachFieldUnderItsOwnAttribute() {
        LogRec r = traced("c", TraceEvent.PROGRESS, Map.of("f.stage", "reading", "f.done", 1L));

        assertThat(r.attr(Semconv.EVENT_NAME)).isEqualTo(Semconv.EV_PROGRESS);
        assertThat(r.attr("loom.data.f.stage")).isEqualTo("reading");
        assertThat(r.attr("loom.data.f.done")).isEqualTo(1L);
    }

    @Test
    void aSignalBecomesARecordNamingTheOpThePeerAndTheNameAndNeverTheText() {
        LogRec r = traced("p", TraceEvent.SIGNAL, Map.of("op", "sent", "peer", "c", "name", "go", "sticky", false));

        assertThat(r.attr(Semconv.EVENT_NAME)).isEqualTo(Semconv.EV_SIGNAL);
        assertThat(r.attr("loom.data.op")).isEqualTo("sent");
        assertThat(r.attr("loom.data.peer")).isEqualTo("c");
        assertThat(r.attr("loom.data.name")).isEqualTo("go");
        assertThat(r.attr("loom.data.sticky")).isEqualTo(false);
    }

    @Test
    void aLongReportedValueIsContentSoAMetadataOnlyConsoleDoesNotGetIt() {
        String long400 = "x".repeat(400);
        LogRec r = new TraceMapper("c", Detail.METADATA).map(new TraceEvent(TraceEvent.PROGRESS, null, "p", "p", Map.of("f.stage", long400), T0)).logs().get(0);

        assertThat(String.valueOf(r.attr("loom.data.f.stage"))).as("content is held back at the metadata level").doesNotContain(long400);
    }

    @Test
    void theRecordsFeedTheParentsChildrenRowThroughTheRegistry() {
        var registry = new io.github.llm4j.loom.inspect.state.RunRegistry();
        TraceMapper parent = new TraceMapper("p", Detail.FULL);
        TraceMapper child = new TraceMapper("child-1", Detail.FULL);
        Map<String, Object> startData = new LinkedHashMap<>();
        startData.put("workflow", "Main");
        startData.put("run", "p");
        java.util.List<LogRec> logs = new java.util.ArrayList<>();
        logs.addAll(parent.map(new TraceEvent(TraceEvent.RUN_START, null, "Main", "workflow Main", startData, T0)).logs());
        Map<String, Object> childStart = new LinkedHashMap<>();
        childStart.put("workflow", "Research");
        childStart.put("run", "child-1");
        childStart.put("parent", "p");
        logs.addAll(child.map(new TraceEvent(TraceEvent.RUN_START, null, "Research", "workflow Research", childStart, T0)).logs());
        logs.addAll(child.map(new TraceEvent(TraceEvent.PROGRESS, null, "report", "report", Map.of("f.stage", "reading"), T0)).logs());
        logs.addAll(parent.map(new TraceEvent(TraceEvent.SIGNAL, null, "signal", "signal", Map.of("op", "sent", "peer", "child-1", "name", "go", "sticky", false), T0)).logs());
        registry.applyLogs(io.github.llm4j.loom.inspect.otel.Resource.of("local", "i"), logs);

        var parentState = registry.findByRunId("local", "p").orElseThrow();
        var row = registry.childrenOf(parentState).get(0);
        assertThat(row).containsKey("progress");
        @SuppressWarnings("unchecked")
        Map<String, Object> signals = (Map<String, Object>) row.get("signals");
        assertThat(signals).as("one sent, none read by the child yet").containsEntry("toChild", 1);
    }
}
