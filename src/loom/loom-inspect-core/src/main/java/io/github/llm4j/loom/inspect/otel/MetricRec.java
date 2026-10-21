package io.github.llm4j.loom.inspect.otel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A metric: a sum (a counter) or a gauge, with its data points. */
public record MetricRec(
        String name, String unit, Type type, boolean monotonic, List<Point> points) {

    public enum Type {
        SUM,
        GAUGE
    }

    /**
     * One data point; {@code value} is a Long (written as {@code asInt}) or a Double ({@code
     * asDouble}).
     */
    public record Point(
            long startNanos, long timeNanos, Number value, Map<String, Object> attributes) {
        public Point {
            attributes =
                    attributes == null
                            ? Map.of()
                            : java.util.Collections.unmodifiableMap(
                                    new LinkedHashMap<>(attributes));
        }
    }

    public MetricRec {
        points = points == null ? List.of() : List.copyOf(points);
    }
}
