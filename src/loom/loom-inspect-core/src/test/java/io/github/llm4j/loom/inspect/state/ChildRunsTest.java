package io.github.llm4j.loom.inspect.state;

import static org.assertj.core.api.Assertions.assertThat;

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

/** Phase 33, D16: the registry stays flat, a child's records carry a parent attribute, and a parent shows its children and their cost. */
class ChildRunsTest {

    static final long T0 = OtlpJson.nanos(Instant.parse("2026-10-14T10:00:00Z"));
    private long seq;

    private LogRec start(String run, String parent, String workflow) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.EVENT_NAME, Semconv.EV_RUN_START);
        a.put(Semconv.LOOM_SEQ, ++seq);
        a.put(Semconv.LOOM_RUN_ID, run);
        a.put(Semconv.LOOM_WORKFLOW, workflow);
        if (parent != null) a.put(Semconv.LOOM_PARENT_RUN_ID, parent);
        return new LogRec(T0 + seq, LogRec.INFO, "start", a, Ids.traceId(run), null);
    }

    private LogRec end(String run, String status) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.EVENT_NAME, Semconv.EV_RUN_END);
        a.put(Semconv.LOOM_SEQ, ++seq);
        a.put(Semconv.LOOM_RUN_ID, run);
        a.put(Semconv.LOOM_RUN_STATUS, status);
        return new LogRec(T0 + seq, LogRec.INFO, "end", a, Ids.traceId(run), null);
    }

    private RunRegistry registry() {
        RunRegistry registry = new RunRegistry();
        registry.applyLogs(
                Resource.of("local", "i_1"),
                List.of(
                        start("p", null, "Main"),
                        start("c1", "p", "Research"),
                        start("c2", "p", "Research"),
                        end("c1", Semconv.STATUS_COMPLETED)));
        return registry;
    }

    @Test
    void aChildsSummaryNamesItsParentAndAParentCountsItsChildrenByStatus() {
        var all = registry().summaries();

        var child = all.stream().filter(s -> "c1".equals(s.get("runId"))).findFirst().orElseThrow();
        var parent = all.stream().filter(s -> "p".equals(s.get("runId"))).findFirst().orElseThrow();
        assertThat(child.get("parent")).isEqualTo("p");
        assertThat(parent).doesNotContainKey("parent");
        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>) parent.get("children");
        assertThat(counts).containsEntry("finished", 1).containsEntry("running", 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) parent.get("totals");
        assertThat(totals).containsKey("costWithChildren");
    }

    @Test
    void aParentsPageListsItsChildren() {
        RunRegistry registry = registry();
        RunState parent = registry.findByRunId("local", "p").orElseThrow();

        var children = registry.childrenOf(parent);

        assertThat(children).hasSize(2).allSatisfy(c -> assertThat(c.get("parent")).isEqualTo("p"));
        assertThat(registry.childrenOf(registry.findByRunId("local", "c1").orElseThrow())).isEmpty();
    }
}
