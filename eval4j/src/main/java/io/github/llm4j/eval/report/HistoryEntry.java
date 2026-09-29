package io.github.llm4j.eval.report;

import java.util.Map;

/** One run's per-metric average scores, as appended to a {@link ScoreHistory}. */
public record HistoryEntry(
        String runId, String timestamp, String gitSha, Map<String, Double> metricAverages) {}
