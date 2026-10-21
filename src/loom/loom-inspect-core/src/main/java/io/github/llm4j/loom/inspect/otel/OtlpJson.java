package io.github.llm4j.loom.inspect.otel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes OTLP/HTTP in its JSON encoding (the shape an OpenTelemetry Collector accepts at
 * {@code /v1/traces}, {@code /v1/logs} and {@code /v1/metrics}), with no OpenTelemetry SDK. The
 * encoding rules that matter: 64-bit integers and timestamps are written as JSON strings, ids are
 * lowercase hex, attribute values are tagged ({@code stringValue}, {@code intValue}, {@code
 * doubleValue}, {@code boolValue}). The decoder is tolerant: unknown fields are ignored and
 * integers may be numbers or strings.
 */
public final class OtlpJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SCOPE_NAME = "loom-inspect";
    private static final int CUMULATIVE = 2;

    private OtlpJson() {}

    /** A decoded batch of spans from one resource. */
    public record Traces(Resource resource, List<SpanRec> spans) {}

    /** A decoded batch of log records from one resource. */
    public record Logs(Resource resource, List<LogRec> records) {}

    /** A decoded batch of metrics from one resource. */
    public record Metrics(Resource resource, List<MetricRec> metrics) {}

    /** Nanoseconds since the epoch for an instant. */
    public static long nanos(Instant t) {
        return Math.addExact(Math.multiplyExact(t.getEpochSecond(), 1_000_000_000L), t.getNano());
    }

    public static Instant instant(long nanos) {
        return Instant.ofEpochSecond(
                Math.floorDiv(nanos, 1_000_000_000L), Math.floorMod(nanos, 1_000_000_000L));
    }

    // ------------------------------------------------------------------------------ encode

    public static String encodeTraces(Resource resource, String version, List<SpanRec> spans) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode rs = root.putArray("resourceSpans").addObject();
        rs.set("resource", resource(resource));
        ObjectNode ss = rs.putArray("scopeSpans").addObject();
        ss.set("scope", scope(version));
        ArrayNode out = ss.putArray("spans");
        for (SpanRec s : spans) {
            ObjectNode n = out.addObject();
            n.put("traceId", s.traceId());
            n.put("spanId", s.spanId());
            if (s.parentSpanId() != null) n.put("parentSpanId", s.parentSpanId());
            n.put("name", s.name());
            n.put("kind", s.kind());
            n.put("startTimeUnixNano", Long.toString(s.startNanos()));
            n.put("endTimeUnixNano", Long.toString(s.endNanos()));
            n.set("attributes", attributes(s.attributes()));
            ObjectNode status = n.putObject("status");
            status.put("code", s.statusCode());
            if (s.statusMessage() != null) status.put("message", s.statusMessage());
            if (!s.events().isEmpty()) {
                ArrayNode events = n.putArray("events");
                for (EventRec e : s.events()) {
                    ObjectNode en = events.addObject();
                    en.put("timeUnixNano", Long.toString(e.timeNanos()));
                    en.put("name", e.name());
                    en.set("attributes", attributes(e.attributes()));
                }
            }
        }
        return write(root);
    }

    public static String encodeLogs(Resource resource, String version, List<LogRec> records) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode rl = root.putArray("resourceLogs").addObject();
        rl.set("resource", resource(resource));
        ObjectNode sl = rl.putArray("scopeLogs").addObject();
        sl.set("scope", scope(version));
        ArrayNode out = sl.putArray("logRecords");
        for (LogRec r : records) {
            ObjectNode n = out.addObject();
            n.put("timeUnixNano", Long.toString(r.timeNanos()));
            n.put("severityNumber", r.severityNumber());
            n.putObject("body").put("stringValue", r.body());
            n.set("attributes", attributes(r.attributes()));
            if (r.traceId() != null) n.put("traceId", r.traceId());
            if (r.spanId() != null) n.put("spanId", r.spanId());
        }
        return write(root);
    }

    public static String encodeMetrics(Resource resource, String version, List<MetricRec> metrics) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode rm = root.putArray("resourceMetrics").addObject();
        rm.set("resource", resource(resource));
        ObjectNode sm = rm.putArray("scopeMetrics").addObject();
        sm.set("scope", scope(version));
        ArrayNode out = sm.putArray("metrics");
        for (MetricRec m : metrics) {
            ObjectNode n = out.addObject();
            n.put("name", m.name());
            if (m.unit() != null) n.put("unit", m.unit());
            ObjectNode body = n.putObject(m.type() == MetricRec.Type.SUM ? "sum" : "gauge");
            ArrayNode points = body.putArray("dataPoints");
            for (MetricRec.Point p : m.points()) {
                ObjectNode pn = points.addObject();
                pn.put("startTimeUnixNano", Long.toString(p.startNanos()));
                pn.put("timeUnixNano", Long.toString(p.timeNanos()));
                if (p.value() instanceof Long || p.value() instanceof Integer)
                    pn.put("asInt", Long.toString(p.value().longValue()));
                else pn.put("asDouble", p.value().doubleValue());
                pn.set("attributes", attributes(p.attributes()));
            }
            if (m.type() == MetricRec.Type.SUM) {
                body.put("aggregationTemporality", CUMULATIVE);
                body.put("isMonotonic", m.monotonic());
            }
        }
        return write(root);
    }

    private static ObjectNode scope(String version) {
        ObjectNode scope = MAPPER.createObjectNode();
        scope.put("name", SCOPE_NAME);
        if (version != null) scope.put("version", version);
        return scope;
    }

    private static ObjectNode resource(Resource r) {
        ObjectNode n = MAPPER.createObjectNode();
        n.set("attributes", attributes(r.attributes()));
        return n;
    }

    private static ArrayNode attributes(Map<String, Object> attrs) {
        ArrayNode array = MAPPER.createArrayNode();
        for (Map.Entry<String, Object> e : attrs.entrySet()) {
            if (e.getValue() == null) continue;
            ObjectNode a = array.addObject();
            a.put("key", e.getKey());
            ObjectNode v = a.putObject("value");
            Object x = e.getValue();
            if (x instanceof Boolean b) v.put("boolValue", b);
            else if (x instanceof Integer
                    || x instanceof Long
                    || x instanceof Short
                    || x instanceof Byte)
                v.put("intValue", Long.toString(((Number) x).longValue()));
            else if (x instanceof Float || x instanceof Double)
                v.put("doubleValue", ((Number) x).doubleValue());
            else if (x instanceof BigDecimal d) v.put("doubleValue", d.doubleValue());
            else v.put("stringValue", String.valueOf(x));
        }
        return array;
    }

    private static String write(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------------------ decode

    public static List<Traces> decodeTraces(String json) {
        List<Traces> out = new ArrayList<>();
        for (JsonNode rs : array(parse(json), "resourceSpans")) {
            Resource resource = resource(rs.get("resource"));
            List<SpanRec> spans = new ArrayList<>();
            for (JsonNode ss : array(rs, "scopeSpans")) {
                for (JsonNode s : array(ss, "spans")) {
                    List<EventRec> events = new ArrayList<>();
                    for (JsonNode e : array(s, "events")) {
                        events.add(
                                new EventRec(
                                        nanosOf(e.get("timeUnixNano")),
                                        text(e, "name"),
                                        attributes(e.get("attributes"))));
                    }
                    JsonNode status = s.get("status");
                    spans.add(
                            new SpanRec(
                                    text(s, "traceId"),
                                    text(s, "spanId"),
                                    blankToNull(text(s, "parentSpanId")),
                                    text(s, "name"),
                                    s.path("kind").asInt(SpanRec.INTERNAL),
                                    nanosOf(s.get("startTimeUnixNano")),
                                    nanosOf(s.get("endTimeUnixNano")),
                                    attributes(s.get("attributes")),
                                    status == null ? 0 : status.path("code").asInt(0),
                                    status == null ? null : blankToNull(text(status, "message")),
                                    events));
                }
            }
            out.add(new Traces(resource, spans));
        }
        return out;
    }

    public static List<Logs> decodeLogs(String json) {
        List<Logs> out = new ArrayList<>();
        for (JsonNode rl : array(parse(json), "resourceLogs")) {
            Resource resource = resource(rl.get("resource"));
            List<LogRec> records = new ArrayList<>();
            for (JsonNode sl : array(rl, "scopeLogs")) {
                for (JsonNode r : array(sl, "logRecords")) {
                    JsonNode body = r.get("body");
                    records.add(
                            new LogRec(
                                    nanosOf(r.get("timeUnixNano")),
                                    r.path("severityNumber").asInt(LogRec.INFO),
                                    body == null ? "" : text(body, "stringValue"),
                                    attributes(r.get("attributes")),
                                    blankToNull(text(r, "traceId")),
                                    blankToNull(text(r, "spanId"))));
                }
            }
            out.add(new Logs(resource, records));
        }
        return out;
    }

    public static List<Metrics> decodeMetrics(String json) {
        List<Metrics> out = new ArrayList<>();
        for (JsonNode rm : array(parse(json), "resourceMetrics")) {
            Resource resource = resource(rm.get("resource"));
            List<MetricRec> metrics = new ArrayList<>();
            for (JsonNode sm : array(rm, "scopeMetrics")) {
                for (JsonNode m : array(sm, "metrics")) {
                    boolean sum = m.has("sum");
                    JsonNode body = sum ? m.get("sum") : m.get("gauge");
                    if (body == null) continue;
                    List<MetricRec.Point> points = new ArrayList<>();
                    for (JsonNode p : array(body, "dataPoints")) {
                        Number value =
                                p.has("asInt")
                                        ? (Number) p.get("asInt").asLong()
                                        : (Number) p.path("asDouble").asDouble();
                        points.add(
                                new MetricRec.Point(
                                        nanosOf(p.get("startTimeUnixNano")),
                                        nanosOf(p.get("timeUnixNano")),
                                        value,
                                        attributes(p.get("attributes"))));
                    }
                    metrics.add(
                            new MetricRec(
                                    text(m, "name"),
                                    blankToNull(text(m, "unit")),
                                    sum ? MetricRec.Type.SUM : MetricRec.Type.GAUGE,
                                    sum && body.path("isMonotonic").asBoolean(false),
                                    points));
                }
            }
            out.add(new Metrics(resource, metrics));
        }
        return out;
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("not valid JSON: " + e.getOriginalMessage(), e);
        }
    }

    private static Iterable<JsonNode> array(JsonNode parent, String field) {
        JsonNode n = parent == null ? null : parent.get(field);
        return n != null && n.isArray() ? n : List.<JsonNode>of();
    }

    private static Resource resource(JsonNode n) {
        return new Resource(n == null ? Map.of() : attributes(n.get("attributes")));
    }

    private static Map<String, Object> attributes(JsonNode array) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (array == null || !array.isArray()) return out;
        for (JsonNode a : array) {
            String key = text(a, "key");
            JsonNode v = a.get("value");
            if (key == null || v == null) continue;
            if (v.has("stringValue")) out.put(key, v.get("stringValue").asText());
            else if (v.has("intValue")) out.put(key, v.get("intValue").asLong());
            else if (v.has("doubleValue")) out.put(key, v.get("doubleValue").asDouble());
            else if (v.has("boolValue")) out.put(key, v.get("boolValue").asBoolean());
        }
        return out;
    }

    private static long nanosOf(JsonNode n) {
        return n == null || n.isNull() ? 0L : n.asLong();
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String blankToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
