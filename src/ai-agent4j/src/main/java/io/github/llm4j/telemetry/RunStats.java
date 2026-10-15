package io.github.llm4j.telemetry;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** An immutable snapshot of statistics: per model and per tool, with totals. Keys are in alphabetical order. */
public record RunStats(Map<String, ModelStats> models, Map<String, ToolStats> tools) {

    public static final RunStats EMPTY = new RunStats(Map.of(), Map.of());

    public RunStats {
        models = Collections.unmodifiableMap(new TreeMap<>(models));
        tools = Collections.unmodifiableMap(new TreeMap<>(tools));
    }

    /** The statistics of one tool; a tool never called gives a result with no calls rather than null. */
    public ToolStats tool(String name) {
        ToolStats s = tools.get(name);
        return s != null ? s : ToolStats.none(name);
    }

    /** The statistics of one model; a model never called gives a result with no calls rather than null. */
    public ModelStats model(String name) {
        ModelStats s = models.get(name);
        return s != null ? s : ModelStats.none(name);
    }

    public long totalModelCalls() {
        return models.values().stream().mapToLong(ModelStats::calls).sum();
    }

    public long totalModelFailures() {
        return models.values().stream().mapToLong(ModelStats::failed).sum();
    }

    public long totalToolCalls() {
        return tools.values().stream().mapToLong(ToolStats::calls).sum();
    }

    public long totalToolFailures() {
        return tools.values().stream().mapToLong(ToolStats::failed).sum();
    }

    public long totalRetries() {
        return models.values().stream().mapToLong(ModelStats::retries).sum();
    }

    public long totalFailovers() {
        return models.values().stream().mapToLong(ModelStats::failovers).sum();
    }

    /** Answers cut off by the token limit, over all models. */
    public long truncatedAnswers() {
        return models.values().stream().mapToLong(ModelStats::truncated).sum();
    }
}
