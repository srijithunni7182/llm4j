package io.github.llm4j.eval.export;

import java.util.Locale;
import java.util.Map;

/**
 * Identifies and classifies a metric. {@code id} is a slug of the name. Built-in metrics carry a
 * default family, facet and dimension (see {@link #of(String)}); a team classifies its own metrics
 * with {@link #dimension(String)}, {@link #family(String)} and {@link #facet(String)}.
 *
 * @param threshold the pass threshold for judged metrics and 1 for assertions; {@code null} for
 *     measured metrics (their pass rule is the budget)
 * @param unit for measured metrics, for example {@code s} or {@code tokens}
 * @param budget for measured metrics, the limit a value must stay within
 */
public record MetricRef(
        String id,
        String name,
        Kind kind,
        String family,
        String facet,
        String dimension,
        Double threshold,
        String unit,
        Double budget) {

    private record Defaults(Kind kind, String family, String facet, String dimension) {}

    private static final Map<String, Defaults> BUILT_IN =
            Map.ofEntries(
                    Map.entry(
                            "correctness",
                            new Defaults(Kind.JUDGE, "agents", "answers", "correctness")),
                    Map.entry(
                            "answer relevancy",
                            new Defaults(Kind.JUDGE, "agents", "answers", "relevancy")),
                    Map.entry(
                            "faithfulness",
                            new Defaults(Kind.JUDGE, "agents", "answers", "grounding")),
                    Map.entry(
                            "groundedness",
                            new Defaults(Kind.JUDGE, "agents", "answers", "grounding")),
                    Map.entry(
                            "hallucination-free",
                            new Defaults(Kind.JUDGE, "agents", "answers", "grounding")),
                    Map.entry(
                            "task completion",
                            new Defaults(Kind.JUDGE, "agents", "answers", "correctness")),
                    Map.entry("toxicity", new Defaults(Kind.JUDGE, "agents", "answers", "safety")),
                    Map.entry("bias", new Defaults(Kind.JUDGE, "agents", "answers", "safety")),
                    Map.entry(
                            "contextual precision",
                            new Defaults(Kind.JUDGE, "retrieval", "rag", "retrieval")),
                    Map.entry(
                            "contextual recall",
                            new Defaults(Kind.JUDGE, "retrieval", "rag", "retrieval")),
                    Map.entry(
                            "contextual relevancy",
                            new Defaults(Kind.JUDGE, "retrieval", "rag", "retrieval")),
                    Map.entry(
                            "knowledge retention",
                            new Defaults(Kind.JUDGE, "conversations", "multi", "correctness")),
                    Map.entry(
                            "conversation completeness",
                            new Defaults(Kind.JUDGE, "conversations", "multi", "correctness")),
                    Map.entry(
                            "role adherence",
                            new Defaults(Kind.JUDGE, "conversations", "multi", "safety")),
                    Map.entry(
                            "conversation relevancy",
                            new Defaults(Kind.JUDGE, "conversations", "multi", "relevancy")),
                    Map.entry(
                            "optimizer: best validation",
                            new Defaults(Kind.MEASURED, "prompts", "optimization", "prompting")));

    /**
     * A metric with its default classification: built-ins are known, anything else is an
     * unclassified judge.
     */
    public static MetricRef of(String name) {
        Defaults d = BUILT_IN.get(name == null ? "" : name.toLowerCase(Locale.ROOT));
        if (d == null) {
            return new MetricRef(
                    slug(name), name, Kind.JUDGE, "agents", "answers", null, null, null, null);
        }
        return new MetricRef(
                slug(name), name, d.kind(), d.family(), d.facet(), d.dimension(), null, null, null);
    }

    /** A deterministic-check metric with an explicit id and classification. */
    public static MetricRef assertion(
            String id, String name, String family, String facet, String dimension) {
        return new MetricRef(id, name, Kind.ASSERTION, family, facet, dimension, 1.0, null, null);
    }

    /** A measured metric: a value compared with a budget. */
    public static MetricRef measured(
            String id, String name, String family, String facet, String dimension, String unit) {
        return new MetricRef(id, name, Kind.MEASURED, family, facet, dimension, null, unit, null);
    }

    public MetricRef kind(Kind newKind) {
        return new MetricRef(id, name, newKind, family, facet, dimension, threshold, unit, budget);
    }

    public MetricRef family(String newFamily) {
        return new MetricRef(
                id, name, kind, slug(newFamily), facet, dimension, threshold, unit, budget);
    }

    public MetricRef facet(String newFacet) {
        return new MetricRef(
                id, name, kind, family, slug(newFacet), dimension, threshold, unit, budget);
    }

    public MetricRef dimension(String newDimension) {
        return new MetricRef(
                id,
                name,
                kind,
                family,
                facet,
                newDimension == null ? null : slug(newDimension),
                threshold,
                unit,
                budget);
    }

    public MetricRef threshold(Double newThreshold) {
        return new MetricRef(id, name, kind, family, facet, dimension, newThreshold, unit, budget);
    }

    public MetricRef budget(Double newBudget, String newUnit) {
        return new MetricRef(
                id, name, kind, family, facet, dimension, threshold, newUnit, newBudget);
    }

    /** Lower-case, every run of non-alphanumeric characters becomes {@code -}, trimmed. */
    public static String slug(String name) {
        if (name == null || name.isBlank()) {
            return "metric";
        }
        String s =
                name.toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9]+", "-")
                        .replaceAll("^-+|-+$", "");
        if (s.isEmpty()) {
            return "metric";
        }
        if (!Character.isLetter(s.charAt(0))) {
            s = "m-" + s;
        }
        return s.length() > 64 ? s.substring(0, 64) : s;
    }
}
