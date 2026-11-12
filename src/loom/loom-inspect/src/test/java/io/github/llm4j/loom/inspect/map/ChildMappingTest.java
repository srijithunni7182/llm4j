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
}
