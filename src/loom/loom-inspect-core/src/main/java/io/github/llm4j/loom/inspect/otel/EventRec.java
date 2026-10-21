package io.github.llm4j.loom.inspect.otel;

import java.util.LinkedHashMap;
import java.util.Map;

/** An event on a span. */
public record EventRec(long timeNanos, String name, Map<String, Object> attributes) {

    public EventRec {
        attributes =
                attributes == null
                        ? Map.of()
                        : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }
}
