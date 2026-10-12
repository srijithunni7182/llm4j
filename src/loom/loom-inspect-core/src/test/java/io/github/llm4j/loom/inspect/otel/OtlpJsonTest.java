package io.github.llm4j.loom.inspect.otel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OtlpJsonTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Resource RESOURCE = Resource.of("getviral-prod", "i_52c1");
    private static final long T = OtlpJson.nanos(Instant.parse("2026-10-11T14:08:52.123456789Z"));

    private static Map<String, Object> attrs(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static SpanRec span() {
        return new SpanRec(Ids.traceId("r-1"), Ids.spanId("r-1", "Main/s1"), Ids.spanId("r-1", "root"), "invoke_agent Editor", SpanRec.CLIENT,
                T, T + 2_000_000_000L, attrs("gen_ai.agent.name", "Editor", "loom.usage.total_tokens", 5000L, "loom.cost.usd", 0.27, "ok", true),
                SpanRec.STATUS_ERROR, "boom", List.of(new EventRec(T + 1, "loom.rewind", attrs("loom.rewind.to", "polish"))));
    }

    @Test
    void tracesAreWrittenInTheOtlpJsonShape() throws Exception {
        JsonNode root = JSON.readTree(OtlpJson.encodeTraces(RESOURCE, "0.1", List.of(span())));
        JsonNode s = root.at("/resourceSpans/0/scopeSpans/0/spans/0");
        assertThat(s.get("traceId").asText()).hasSize(32);
        assertThat(s.get("spanId").asText()).hasSize(16);
        assertThat(s.get("startTimeUnixNano").isTextual()).as("64-bit timestamps are JSON strings").isTrue();
        assertThat(s.get("startTimeUnixNano").asText()).isEqualTo(Long.toString(T));
        assertThat(s.get("kind").asInt()).isEqualTo(3);
        assertThat(s.at("/status/code").asInt()).isEqualTo(2);
        assertThat(root.at("/resourceSpans/0/resource/attributes/0/key").asText()).isEqualTo("service.name");
        assertThat(root.at("/resourceSpans/0/scopeSpans/0/scope/name").asText()).isEqualTo("loom-inspect");
        JsonNode tokens = findAttr(s.get("attributes"), "loom.usage.total_tokens");
        assertThat(tokens.get("intValue").isTextual()).as("int64 attribute values are JSON strings").isTrue();
        assertThat(tokens.get("intValue").asText()).isEqualTo("5000");
        assertThat(findAttr(s.get("attributes"), "loom.cost.usd").get("doubleValue").asDouble()).isEqualTo(0.27);
        assertThat(findAttr(s.get("attributes"), "ok").get("boolValue").asBoolean()).isTrue();
    }

    private static JsonNode findAttr(JsonNode array, String key) {
        for (JsonNode a : array) if (key.equals(a.get("key").asText())) return a.get("value");
        throw new AssertionError("no attribute " + key);
    }

    @Test
    void spansSurviveARoundTrip() {
        SpanRec in = span();
        List<OtlpJson.Traces> out = OtlpJson.decodeTraces(OtlpJson.encodeTraces(RESOURCE, "0.1", List.of(in)));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).resource().serviceName()).isEqualTo("getviral-prod");
        assertThat(out.get(0).resource().instanceId()).isEqualTo("i_52c1");
        assertThat(out.get(0).spans()).containsExactly(in);
    }

    @Test
    void logsSurviveARoundTrip() {
        LogRec in = new LogRec(T, LogRec.WARN, "paused at Main/s1", attrs("event.name", "loom.run.suspended", "loom.seq", 7L), Ids.traceId("r-1"), Ids.spanId("r-1", "x"));
        List<OtlpJson.Logs> out = OtlpJson.decodeLogs(OtlpJson.encodeLogs(RESOURCE, "0.1", List.of(in)));
        assertThat(out.get(0).records()).containsExactly(in);
        assertThat(out.get(0).records().get(0).seq()).isEqualTo(7L);
        assertThat(out.get(0).records().get(0).eventName()).isEqualTo("loom.run.suspended");
    }

    @Test
    void aLogWithoutIdsKeepsThemNull() {
        LogRec in = new LogRec(T, LogRec.INFO, "hello", attrs("event.name", "loom.note"), null, null);
        LogRec out = OtlpJson.decodeLogs(OtlpJson.encodeLogs(RESOURCE, null, List.of(in))).get(0).records().get(0);
        assertThat(out.traceId()).isNull();
        assertThat(out.spanId()).isNull();
    }

    @Test
    void metricsSurviveARoundTripAsSumAndGauge() {
        MetricRec sum = new MetricRec("loom.run.cost", "USD", MetricRec.Type.SUM, true,
                List.of(new MetricRec.Point(T, T + 5, 1.84, attrs("loom.workflow", "ContentStudio"))));
        MetricRec gauge = new MetricRec("loom.runs.active", "{run}", MetricRec.Type.GAUGE, false, List.of(new MetricRec.Point(T, T + 5, 3L, Map.of())));
        List<MetricRec> out = OtlpJson.decodeMetrics(OtlpJson.encodeMetrics(RESOURCE, "0.1", List.of(sum, gauge))).get(0).metrics();
        assertThat(out).containsExactly(sum, gauge);
    }

    @Test
    void theDecoderAcceptsNumbersForIntegersAndIgnoresUnknownFields() {
        String json = """
                {"resourceLogs":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"p"}}],"future":1},
                 "scopeLogs":[{"scope":{"name":"x"},"logRecords":[
                  {"timeUnixNano":1700000000000000000,"severityNumber":9,"body":{"stringValue":"hi"},"flags":1,
                   "attributes":[{"key":"loom.seq","value":{"intValue":12}}]}]}]}]}
                """;
        LogRec r = OtlpJson.decodeLogs(json).get(0).records().get(0);
        assertThat(r.timeNanos()).isEqualTo(1_700_000_000_000_000_000L);
        assertThat(r.seq()).isEqualTo(12L);
        assertThat(r.body()).isEqualTo("hi");
    }

    @Test
    void notJsonIsAClearError() {
        assertThatThrownBy(() -> OtlpJson.decodeTraces("{nope")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not valid JSON");
    }

    @Test
    void nanosAndInstantsConvertBothWays() {
        Instant t = Instant.parse("2026-10-11T14:08:52.123456789Z");
        assertThat(OtlpJson.instant(OtlpJson.nanos(t))).isEqualTo(t);
    }
}
