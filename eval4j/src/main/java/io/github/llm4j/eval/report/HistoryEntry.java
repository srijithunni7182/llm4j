package io.github.llm4j.eval.report;

import java.util.Map;

/**
 * One run's summary, as appended to a {@link ScoreHistory}: per-metric average scores for trend
 * charts, plus the run's pass rate and per-case scores so the next report can show what changed.
 * {@code passRate}, {@code evaluations} and {@code caseScores} are {@code null} for entries written
 * before the dashboard existed.
 */
public record HistoryEntry(
        String runId,
        String timestamp,
        String gitSha,
        Map<String, Double> metricAverages,
        Double passRate,
        Integer evaluations,
        Map<String, Double> caseScores) {

    public HistoryEntry(
            String runId, String timestamp, String gitSha, Map<String, Double> metricAverages) {
        this(runId, timestamp, gitSha, metricAverages, null, null, null);
    }
}
