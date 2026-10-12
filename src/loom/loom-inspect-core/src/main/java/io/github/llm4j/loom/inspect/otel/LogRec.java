package io.github.llm4j.loom.inspect.otel;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A log record. loom-inspect uses them for the live lifecycle (a step started, a run was suspended, a thought, a note): the
 * {@code event.name} attribute says which, and {@code loom.seq} orders them within a process.
 *
 * @param severityNumber OpenTelemetry severity: 9 info, 13 warn, 17 error
 * @param traceId the run's trace id, or null
 * @param spanId the span the record belongs to, or null
 */
public record LogRec(long timeNanos, int severityNumber, String body, Map<String, Object> attributes, String traceId, String spanId) {

    public static final int INFO = 9;
    public static final int WARN = 13;
    public static final int ERROR = 17;

    public LogRec {
        attributes = attributes == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        body = body == null ? "" : body;
    }

    public String eventName() {
        Object v = attributes.get(Semconv.EVENT_NAME);
        return v == null ? "" : String.valueOf(v);
    }

    public Object attr(String key) {
        return attributes.get(key);
    }

    public String text(String key) {
        Object v = attributes.get(key);
        return v == null ? null : String.valueOf(v);
    }

    /** The {@code loom.seq} number, or -1 when the record has none. */
    public long seq() {
        Object v = attributes.get(Semconv.LOOM_SEQ);
        return v instanceof Number n ? n.longValue() : -1;
    }
}
