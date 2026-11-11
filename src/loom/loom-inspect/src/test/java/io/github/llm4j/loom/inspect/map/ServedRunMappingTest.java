package io.github.llm4j.loom.inspect.map;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.inspect.otel.LogRec;
import io.github.llm4j.loom.inspect.otel.Semconv;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** docs/specs/32-a2a-integration R37: a run that outsiders started says so on its start record. */
class ServedRunMappingTest {

    private static LogRec start(Map<String, Object> extra) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("workflow", "Total");
        data.put("run", "r-1");
        data.putAll(extra);
        var event = new TraceEvent(TraceEvent.RUN_START, null, "Total", "workflow Total", data, Instant.parse("2026-11-11T10:00:00Z"));
        return new TraceMapper("r-1", Detail.FULL).map(event).logs().stream().filter(l -> Semconv.EV_RUN_START.equals(l.eventName())).findFirst().orElseThrow();
    }

    @Test
    void aServedRunCarriesTheMarker() {
        assertThat(start(Map.of("served", "true")).attr(Semconv.LOOM_SERVED)).isEqualTo(true);
    }

    @Test
    void anOrdinaryRunDoesNot() {
        assertThat(start(Map.of()).attr(Semconv.LOOM_SERVED)).isNull();
    }
}
