package io.github.llm4j.loom.inspect.map;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.inspect.otel.LogRec;
import io.github.llm4j.loom.inspect.otel.Semconv;
import io.github.llm4j.loom.inspect.otel.SpanRec;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Phases 24 and 25: an extract step is a model call under the extractor's name (tokens, time,
 * attempts, never the text or the values), and a list over its fan-out bound is a log record with
 * counts and no items.
 */
class ExtractMappingTest {

    private static final Instant T0 = Instant.parse("2026-11-03T10:00:00Z");

    private static TraceMapper mapper(Detail detail) {
        TraceMapper m = new TraceMapper("r-1", detail);
        m.map(new TraceEvent(TraceEvent.RUN_START, null, "", "", Map.of("workflow", "Main", "run", "r-1"), T0));
        return m;
    }

    private static TraceEvent extractEnd() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("extractor", "Invoice");
        d.put("step", "Main/s0");
        d.put("model", "gemini-2.5-flash");
        d.put("attempts", 2);
        d.put("duration_ms", 640L);
        d.put("input_tokens", 310L);
        d.put("output_tokens", 42L);
        d.put("present", List.of("vendor", "currency"));
        d.put("missing", List.of("total"));
        return new TraceEvent(TraceEvent.EXTRACT_END, null, "Main/s0", "Invoice → 2 fields, 1 missing", d, T0.plusMillis(700));
    }

    @Test
    void anExtractStepBecomesAModelSpanUnderTheExtractorsName() {
        List<SpanRec> spans = mapper(Detail.FULL).map(extractEnd()).spans();

        assertThat(spans).hasSize(1);
        SpanRec s = spans.get(0);
        assertThat(s.attr(Semconv.LOOM_SPAN_KIND)).isEqualTo(Semconv.KIND_MODEL);
        assertThat(s.attr(Semconv.GEN_AI_OPERATION_NAME)).isEqualTo("extract");
        assertThat(s.attr(Semconv.GEN_AI_AGENT_NAME)).isEqualTo("Invoice");
        assertThat(s.attr(Semconv.GEN_AI_RESPONSE_MODEL)).isEqualTo("gemini-2.5-flash");
        assertThat(s.attr(Semconv.GEN_AI_USAGE_INPUT_TOKENS)).isEqualTo(310L);
        assertThat(s.attr(Semconv.LOOM_MODEL_ATTEMPTS)).isEqualTo(2L);
        assertThat(s.statusCode()).isEqualTo(SpanRec.STATUS_OK);
    }

    @Test
    void aFanoutExceededEventIsALogRecordWithCountsAndNoItems() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("count", 412);
        d.put("max", 10);
        d.put("list", "plan.tasks");
        d.put("producer", "Planner");
        d.put("producerLine", 20);
        TraceEvent e =
                new TraceEvent(TraceEvent.FANOUT_EXCEEDED, "Planner", "Main/s2", "plan.tasks has 412 items; the bound is 10", d, T0.plusMillis(5));

        List<LogRec> logs = mapper(Detail.METADATA).map(e).logs();

        assertThat(logs).hasSize(1);
        LogRec r = logs.get(0);
        assertThat(r.attributes().get(Semconv.EVENT_NAME)).isEqualTo(Semconv.EV_FANOUT_EXCEEDED);
        assertThat(String.valueOf(r.attributes().get("loom.data.count"))).isEqualTo("412");
        assertThat(String.valueOf(r.attributes().get("loom.data.producer"))).isEqualTo("Planner");
    }
}
