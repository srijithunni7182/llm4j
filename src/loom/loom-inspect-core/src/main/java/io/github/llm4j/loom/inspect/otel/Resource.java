package io.github.llm4j.loom.inspect.otel;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Who is reporting: the attributes of an OpenTelemetry resource ({@code service.name}, {@code
 * service.instance.id}, …).
 */
public record Resource(Map<String, Object> attributes) {

    public Resource {
        attributes =
                attributes == null
                        ? Map.of()
                        : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    public static Resource of(String serviceName, String instanceId) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.SERVICE_NAME, serviceName);
        a.put(Semconv.SERVICE_INSTANCE_ID, instanceId);
        a.put(Semconv.TELEMETRY_SDK_NAME, "loom-inspect");
        return new Resource(a);
    }

    public String serviceName() {
        return String.valueOf(attributes.getOrDefault(Semconv.SERVICE_NAME, ""));
    }

    public String instanceId() {
        return String.valueOf(attributes.getOrDefault(Semconv.SERVICE_INSTANCE_ID, ""));
    }
}
