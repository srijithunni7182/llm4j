package io.github.llm4j.loom.inspect.map;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.inspect.otel.Semconv;
import io.github.llm4j.loom.inspect.otel.SpanRec;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A classification is a model call: it shows in the run's calls and tokens, under the classifier's
 * name, and carries no state text (R12).
 */
class ClassifyMappingTest {

    private static final Instant T0 = Instant.parse("2026-10-21T10:00:00Z");

    private static TraceEvent classify(String outcome) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("classifier", "Intent");
        d.put("step", "Main/s0");
        d.put("backend", "jev");
        d.put("model", "jev-1.13.0");
        d.put("requested_model", "Scorer/jev-latest");
        d.put("calibrated", true);
        d.put("input_tokens", 405L);
        d.put("output_tokens", 78L);
        d.put("duration_ms", 834L);
        d.put("attempts", 2);
        d.put("outcome", outcome);
        d.put("labels", Map.of("kind", "refund"));
        return new TraceEvent(
                "classify", null, "Main/s0", "Intent → kind=refund", d, T0.plusMillis(900));
    }

    @Test
    void aClassificationBecomesAModelSpanWithTokensDurationAndTheClassifiersName() {
        TraceMapper m = new TraceMapper("r-1", Detail.FULL);
        m.map(
                new TraceEvent(
                        TraceEvent.RUN_START,
                        null,
                        "",
                        "",
                        Map.of("workflow", "Main", "run", "r-1"),
                        T0));

        List<SpanRec> spans = m.map(classify("ok")).spans();

        assertThat(spans).hasSize(1);
        SpanRec s = spans.get(0);
        assertThat(s.attr(Semconv.LOOM_SPAN_KIND)).isEqualTo(Semconv.KIND_MODEL);
        assertThat(s.attr(Semconv.GEN_AI_OPERATION_NAME)).isEqualTo("classify");
        assertThat(s.attr(Semconv.GEN_AI_AGENT_NAME)).isEqualTo("Intent");
        assertThat(s.attr(Semconv.GEN_AI_RESPONSE_MODEL)).isEqualTo("jev-1.13.0");
        assertThat(s.attr(Semconv.GEN_AI_USAGE_INPUT_TOKENS)).isEqualTo(405L);
        assertThat(s.attr(Semconv.GEN_AI_USAGE_OUTPUT_TOKENS)).isEqualTo(78L);
        assertThat(s.attr(Semconv.LOOM_MODEL_ATTEMPTS)).isEqualTo(2L);
        assertThat(s.statusCode()).isEqualTo(SpanRec.STATUS_OK);
        assertThat(s.toString()).doesNotContain("refund");
    }

    @Test
    void anUnsureClassificationIsNotAFailure() {
        TraceMapper m = new TraceMapper("r-1", Detail.METADATA);

        SpanRec s = m.map(classify("unsure")).spans().get(0);

        assertThat(s.statusCode()).isEqualTo(SpanRec.STATUS_OK);
    }
}
