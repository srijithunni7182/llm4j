package io.github.llm4j.loom.inspect.otel;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * R14: the chat spans, tool outcome attributes, context and compaction logs and run totals export
 * as valid OTLP and read back unchanged.
 */
class OtlpNewRecordsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Resource RES = Resource.of("p", "i_1");
    private static final long T = OtlpJson.nanos(Instant.parse("2026-10-14T10:00:00Z"));

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], kv[i + 1]);
        return out;
    }

    private static JsonNode attr(JsonNode attributes, String key) {
        for (JsonNode a : attributes) if (key.equals(a.get("key").asText())) return a.get("value");
        throw new AssertionError("no attribute " + key);
    }

    @Test
    void aChatSpanKeepsItsTypedAttributes() throws Exception {
        SpanRec chat =
                new SpanRec(
                        Ids.traceId("r"),
                        Ids.spanId("r", "m1"),
                        Ids.spanId("r", "workflow"),
                        "chat m1",
                        SpanRec.CLIENT,
                        T,
                        T + 400_000_000L,
                        m(
                                Semconv.GEN_AI_OPERATION_NAME,
                                Semconv.OP_CHAT,
                                Semconv.GEN_AI_RESPONSE_MODEL,
                                "m1",
                                Semconv.GEN_AI_USAGE_INPUT_TOKENS,
                                100L,
                                Semconv.GEN_AI_USAGE_OUTPUT_TOKENS,
                                20L,
                                Semconv.GEN_AI_RESPONSE_FINISH_REASONS,
                                "length",
                                Semconv.LOOM_MODEL_ATTEMPTS,
                                3L,
                                Semconv.LOOM_MODEL_WAITED_MS,
                                250L,
                                Semconv.LOOM_MODEL_TRUNCATED,
                                true,
                                Semconv.LOOM_MODEL_FAILOVERS,
                                "A,B",
                                Semconv.LOOM_SPAN_KIND,
                                Semconv.KIND_MODEL,
                                Semconv.ERROR_TYPE,
                                "rate_limited"),
                        SpanRec.STATUS_ERROR,
                        "rate_limited",
                        List.of());
        String json = OtlpJson.encodeTraces(RES, "0.1", List.of(chat));
        JsonNode attrs = JSON.readTree(json).at("/resourceSpans/0/scopeSpans/0/spans/0/attributes");
        assertThat(attr(attrs, Semconv.GEN_AI_USAGE_INPUT_TOKENS).get("intValue").asText())
                .isEqualTo("100");
        assertThat(attr(attrs, Semconv.LOOM_MODEL_TRUNCATED).get("boolValue").asBoolean()).isTrue();
        assertThat(attr(attrs, Semconv.GEN_AI_OPERATION_NAME).get("stringValue").asText())
                .isEqualTo("chat");
        SpanRec back = OtlpJson.decodeTraces(json).get(0).spans().get(0);
        assertThat(back.attributes()).isEqualTo(chat.attributes());
        assertThat(back.name()).isEqualTo("chat m1");
        assertThat(back.statusCode()).isEqualTo(SpanRec.STATUS_ERROR);
    }

    @Test
    void aToolSpanWithItsOutcomeKeepsItsAttributes() {
        SpanRec tool =
                new SpanRec(
                        Ids.traceId("r"),
                        Ids.spanId("r", "t1"),
                        null,
                        "execute_tool Wiki",
                        SpanRec.CLIENT,
                        T,
                        T + 1,
                        m(
                                Semconv.LOOM_TOOL_OUTCOME,
                                "EXECUTED",
                                Semconv.LOOM_TOOL_SOFT_FAILURE,
                                true,
                                Semconv.LOOM_TOOL_RESULT_CHARS,
                                40L,
                                Semconv.LOOM_TOOL_DURATION_MS,
                                120L,
                                Semconv.ERROR_TYPE,
                                "not_found"),
                        SpanRec.STATUS_ERROR,
                        "not_found",
                        List.of());
        SpanRec back =
                OtlpJson.decodeTraces(OtlpJson.encodeTraces(RES, "0.1", List.of(tool)))
                        .get(0)
                        .spans()
                        .get(0);
        assertThat(back.attributes()).isEqualTo(tool.attributes());
    }

    @Test
    void contextAndCompactionLogsKeepTheirNumbers() throws Exception {
        LogRec ctx =
                new LogRec(
                        T,
                        LogRec.INFO,
                        "context 900 tokens",
                        m(
                                Semconv.EVENT_NAME,
                                Semconv.EV_CONTEXT,
                                Semconv.LOOM_SEQ,
                                7L,
                                Semconv.LOOM_CONTEXT_USED,
                                900L,
                                Semconv.LOOM_CONTEXT_WINDOW,
                                3000L,
                                Semconv.LOOM_CONTEXT_FRACTION,
                                0.36,
                                Semconv.LOOM_CONTEXT_SOURCE,
                                "REPORTED"),
                        Ids.traceId("r"),
                        null);
        LogRec cmp =
                new LogRec(
                        T,
                        LogRec.WARN,
                        "failed",
                        m(
                                Semconv.EVENT_NAME,
                                Semconv.EV_COMPACTION,
                                Semconv.LOOM_SEQ,
                                8L,
                                Semconv.LOOM_COMPACTION_STRATEGY,
                                "x",
                                Semconv.LOOM_COMPACTION_BEFORE,
                                2100L,
                                Semconv.LOOM_COMPACTION_AFTER,
                                2100L,
                                Semconv.LOOM_COMPACTION_SUMMARY_COST,
                                "0.0012"),
                        Ids.traceId("r"),
                        null);
        String json = OtlpJson.encodeLogs(RES, "0.1", List.of(ctx, cmp));
        JsonNode first = JSON.readTree(json).at("/resourceLogs/0/scopeLogs/0/logRecords/0");
        assertThat(
                        attr(first.get("attributes"), Semconv.LOOM_CONTEXT_FRACTION)
                                .get("doubleValue")
                                .asDouble())
                .isEqualTo(0.36);
        List<LogRec> back = OtlpJson.decodeLogs(json).get(0).records();
        assertThat(back).hasSize(2);
        assertThat(back.get(0).attributes()).isEqualTo(ctx.attributes());
        assertThat(back.get(1).attributes()).isEqualTo(cmp.attributes());
        assertThat(back.get(1).severityNumber()).isEqualTo(LogRec.WARN);
        assertThat(back.get(0).seq()).isEqualTo(7L);
    }

    @Test
    void runTotalsOnTheRootSpanAndRunEndSurvive() {
        SpanRec root =
                new SpanRec(
                        Ids.traceId("r"),
                        Ids.spanId("r", "workflow"),
                        null,
                        "loom.workflow Main",
                        SpanRec.INTERNAL,
                        T,
                        T + 5,
                        m(
                                Semconv.LOOM_SPAN_KIND,
                                Semconv.KIND_WORKFLOW,
                                Semconv.LOOM_RUN_MODEL_CALLS,
                                15L,
                                Semconv.LOOM_RUN_TOOL_CALLS,
                                14L,
                                Semconv.LOOM_RUN_COMPACTIONS,
                                3L),
                        SpanRec.STATUS_OK,
                        null,
                        List.of());
        SpanRec back =
                OtlpJson.decodeTraces(OtlpJson.encodeTraces(RES, "0.1", List.of(root)))
                        .get(0)
                        .spans()
                        .get(0);
        assertThat(back.attr(Semconv.LOOM_RUN_MODEL_CALLS)).isEqualTo(15L);
        assertThat(back.attr(Semconv.LOOM_RUN_COMPACTIONS)).isEqualTo(3L);
    }
}
