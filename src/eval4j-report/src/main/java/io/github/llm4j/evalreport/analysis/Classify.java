package io.github.llm4j.evalreport.analysis;

import io.github.llm4j.evalreport.format.model.Ev;
import io.github.llm4j.evalreport.format.model.MetricDef;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves family, facet and dimension for an evaluation: its own value, else its metric's, else
 * the defaults ({@code agents}, {@code answers}; the dimension has no default and falls to {@code
 * other}). Spec 03 §5.1.
 */
public final class Classify {

    public record Resolved(
            Ev ev, String family, String facet, String dimension, MetricDef metric) {}

    private final Map<String, MetricDef> metrics = new HashMap<>();

    public Classify(List<MetricDef> defs) {
        for (MetricDef m : defs) {
            metrics.put(m.id(), m);
        }
    }

    public MetricDef metric(String id) {
        return metrics.get(id);
    }

    public Resolved resolve(Ev e) {
        MetricDef m = metrics.get(e.metric());
        String family = first(e.family(), m == null ? null : m.family(), "agents");
        String facet = first(e.facet(), m == null ? null : m.facet(), "answers");
        String dimension = first(e.dimension(), m == null ? null : m.dimension(), "other");
        return new Resolved(e, family, facet, dimension, m);
    }

    private static String first(String a, String b, String fallback) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return fallback;
    }
}
