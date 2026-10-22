package io.github.llm4j.loom.inspect.map;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.inspect.otel.Semconv;
import io.github.llm4j.loom.inspect.otel.SpanRec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** R29: the reach of a tool call reaches its span; a trace without it maps as before. */
class ToolReachMappingTest {

    private static final Instant T0 = Instant.parse("2026-10-14T10:00:00Z");
    private static final String STEP = "Main/s0";

    private static TraceEvent ev(double sec, String type, String text, Object... kv) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) data.put((String) kv[i], kv[i + 1]);
        return new TraceEvent(type, "Editor", STEP, text, data, T0.plusMillis((long) (sec * 1000)));
    }

    private final List<SpanRec> spans = new ArrayList<>();

    private void feed(TraceEvent... events) {
        TraceMapper m = new TraceMapper("r-1", Detail.FULL);
        for (TraceEvent e : events) spans.addAll(m.map(e).spans());
    }

    private SpanRec tool() {
        return spans.stream()
                .filter(s -> Semconv.KIND_TOOL.equals(s.attr(Semconv.LOOM_SPAN_KIND)))
                .findFirst()
                .orElseThrow();
    }

    private TraceEvent[] call(Object... toolCallExtras) {
        Map<String, Object> d =
                new LinkedHashMap<>(
                        Map.of(
                                "tool",
                                "Fetch",
                                "outcome",
                                "EXECUTED",
                                "duration_ms",
                                120L,
                                "approval_ms",
                                0L,
                                "result_chars",
                                40L,
                                "soft_failure",
                                false));
        for (int i = 0; i < toolCallExtras.length; i += 2)
            d.put((String) toolCallExtras[i], toolCallExtras[i + 1]);
        return new TraceEvent[] {
            ev(
                    0,
                    TraceEvent.MODEL_CALL,
                    "m",
                    "model",
                    "m",
                    "duration_ms",
                    100L,
                    "attempts",
                    1,
                    "outcome",
                    "OK"),
            ev(0.1, TraceEvent.ACTION, "Fetch {}"),
            ev(0.2, TraceEvent.OBSERVATION, "page text"),
            new TraceEvent(TraceEvent.TOOL_CALL, "Editor", STEP, "Fetch ok", d, T0.plusMillis(300))
        };
    }

    @Test
    void theSpanCarriesTheReachAndWhetherItIsKnown() {
        feed(call("reach", "outward,private,untrusted", "reach_known", false));
        assertThat(tool().text(Semconv.LOOM_TOOL_REACH)).isEqualTo("outward,private,untrusted");
        assertThat(tool().attr(Semconv.LOOM_TOOL_REACH_KNOWN)).isEqualTo(false);
    }

    @Test
    void anEmptyReachIsKept() {
        feed(call("reach", "", "reach_known", true));
        assertThat(tool().attributes())
                .containsEntry(Semconv.LOOM_TOOL_REACH, "")
                .containsEntry(Semconv.LOOM_TOOL_REACH_KNOWN, true);
    }

    @Test
    void aTraceWithoutReachMapsAsBefore() {
        feed(call());
        assertThat(tool().attributes())
                .doesNotContainKeys(Semconv.LOOM_TOOL_REACH, Semconv.LOOM_TOOL_REACH_KNOWN);
    }
}
