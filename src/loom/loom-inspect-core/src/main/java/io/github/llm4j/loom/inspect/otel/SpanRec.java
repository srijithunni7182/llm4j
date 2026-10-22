package io.github.llm4j.loom.inspect.otel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A finished span. A step is exported when it ends; while it runs, a {@code loom.span.start} log
 * record says it began.
 *
 * @param parentSpanId the parent's id, or null for a root
 * @param kind OpenTelemetry span kind: 1 internal, 2 server, 3 client, 4 producer, 5 consumer
 * @param statusCode 0 unset, 1 ok, 2 error
 */
public record SpanRec(
        String traceId,
        String spanId,
        String parentSpanId,
        String name,
        int kind,
        long startNanos,
        long endNanos,
        Map<String, Object> attributes,
        int statusCode,
        String statusMessage,
        List<EventRec> events) {

    public static final int INTERNAL = 1;
    public static final int CLIENT = 3;
    public static final int STATUS_UNSET = 0;
    public static final int STATUS_OK = 1;
    public static final int STATUS_ERROR = 2;

    public SpanRec {
        attributes =
                attributes == null
                        ? Map.of()
                        : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        events = events == null ? List.of() : List.copyOf(events);
    }

    public Object attr(String key) {
        return attributes.get(key);
    }

    public String text(String key) {
        Object v = attributes.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
