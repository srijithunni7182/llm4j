package io.github.llm4j.eval.report;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Everything the report renderers need, derived once from a run, its history and its baseline:
 * overall counts, per-metric statistics, per-test groupings and what changed since the previous
 * run. Renderers only format; they never recompute.
 */
final class ReportAnalysis {

    /** A score moving by more than this between two runs counts as a change. */
    static final double CHANGE_EPSILON = 0.05;

    /** Histogram bucket count over the 0..1 score range. */
    static final int BUCKETS = 10;

    /** Per-metric statistics. */
    record MetricStats(
            String metric,
            int count,
            int passed,
            double average,
            double min,
            double max,
            double median,
            double threshold,
            int[] histogram,
            Double baselineDelta,
            Double previousDelta,
            List<Double> trend) {

        double passRate() {
            return count == 0 ? 0 : (double) passed / count;
        }

        boolean regressed() {
            return baselineDelta != null && baselineDelta < -CHANGE_EPSILON;
        }
    }

    /** All evaluations that one test produced. */
    record TestGroup(String suite, String testName, List<Integer> recordIndexes) {
        String key() {
            return (suite == null ? "" : suite)
                    + " / "
                    + (testName == null ? "(no test)" : testName);
        }
    }

    /** A case whose score moved between the previous run and this one. */
    record CaseChange(int recordIndex, double previous, double current) {
        double delta() {
            return current - previous;
        }
    }

    final EvalReportWriter.RunInfo run;
    final List<EvalRecord> records;
    final int total;
    final int passed;
    final double passRate;
    final double averageScore;
    final long judgedMillis;
    final Map<String, MetricStats> metrics = new TreeMap<>();
    final List<TestGroup> groups = new ArrayList<>();
    final List<HistoryEntry> history;
    final HistoryEntry previous;

    /** Prior runs followed by this one (oldest first), newest {@link #MAX_TIMELINE} only. */
    final List<HistoryEntry> timeline = new ArrayList<>();

    static final int MAX_TIMELINE = 40;

    /** Cases that passed (or did not exist) last run and fail now. */
    final List<CaseChange> newFailures = new ArrayList<>();

    /** Cases that failed last run and pass now. */
    final List<CaseChange> fixed = new ArrayList<>();

    /** Still-passing or still-failing cases whose score dropped / rose by more than the epsilon. */
    final List<CaseChange> worse = new ArrayList<>();

    final List<CaseChange> better = new ArrayList<>();
    int newCases;

    ReportAnalysis(
            EvalReportWriter.RunInfo run,
            List<HistoryEntry> history,
            Map<String, Double> baselineAverages) {
        this.run = run;
        this.records = run.records() == null ? List.of() : run.records();
        this.history = history == null ? List.of() : history;
        this.previous = lastBefore(this.history, run.runId());
        this.total = records.size();
        this.passed = (int) records.stream().filter(EvalRecord::passed).count();
        this.passRate = total == 0 ? 0 : (double) passed / total;
        this.averageScore = records.stream().mapToDouble(EvalRecord::score).average().orElse(0);
        this.judgedMillis =
                records.stream().mapToLong(r -> r.durationMs() == null ? 0 : r.durationMs()).sum();
        buildTimeline();
        buildMetrics(baselineAverages == null ? Map.of() : baselineAverages);
        buildGroups();
        buildChanges();
    }

    /** Key of a case across runs; matches {@link BaselineGate#key}. */
    static String caseKey(EvalRecord r) {
        return BaselineGate.key(r.suite(), r.testName(), r.metric());
    }

    /** Per-case scores for {@link HistoryEntry#caseScores()}; repeated cases are averaged. */
    static Map<String, Double> caseScores(List<EvalRecord> records) {
        return BaselineGate.aggregate(records);
    }

    private static HistoryEntry lastBefore(List<HistoryEntry> history, String runId) {
        for (int i = history.size() - 1; i >= 0; i--) {
            if (!history.get(i).runId().equals(runId)) {
                return history.get(i);
            }
        }
        return null;
    }

    private void buildTimeline() {
        for (HistoryEntry h : history) {
            if (h.runId() == null || !h.runId().equals(run.runId())) {
                timeline.add(h);
            }
        }
        if (total > 0) {
            timeline.add(
                    new HistoryEntry(
                            run.runId(),
                            run.endedAt(),
                            run.gitSha(),
                            EvalReportWriter.metricAverages(records),
                            passRate,
                            total,
                            null));
        }
        while (timeline.size() > MAX_TIMELINE) {
            timeline.remove(0);
        }
    }

    private void buildMetrics(Map<String, Double> baselineAverages) {
        Map<String, List<EvalRecord>> byMetric = new TreeMap<>();
        for (EvalRecord r : records) {
            byMetric.computeIfAbsent(r.metric(), k -> new ArrayList<>()).add(r);
        }
        byMetric.forEach(
                (metric, mine) -> {
                    double[] scores =
                            mine.stream().mapToDouble(EvalRecord::score).sorted().toArray();
                    double avg = 0;
                    int ok = 0;
                    int[] histogram = new int[BUCKETS];
                    Map<Double, Integer> thresholds = new HashMap<>();
                    for (EvalRecord r : mine) {
                        avg += r.score();
                        if (r.passed()) {
                            ok++;
                        }
                        histogram[bucket(r.score())]++;
                        thresholds.merge(r.threshold(), 1, Integer::sum);
                    }
                    avg /= mine.size();
                    double median =
                            scores.length % 2 == 1
                                    ? scores[scores.length / 2]
                                    : (scores[scores.length / 2 - 1] + scores[scores.length / 2])
                                            / 2;
                    double threshold =
                            thresholds.entrySet().stream()
                                    .max(Map.Entry.comparingByValue())
                                    .get()
                                    .getKey();
                    Double base = baselineAverages.get(metric);
                    Double prev =
                            previous == null || previous.metricAverages() == null
                                    ? null
                                    : previous.metricAverages().get(metric);
                    metrics.put(
                            metric,
                            new MetricStats(
                                    metric,
                                    mine.size(),
                                    ok,
                                    avg,
                                    scores[0],
                                    scores[scores.length - 1],
                                    median,
                                    threshold,
                                    histogram,
                                    base == null ? null : avg - base,
                                    prev == null ? null : avg - prev,
                                    trend(metric)));
                });
    }

    static int bucket(double score) {
        return Math.min(BUCKETS - 1, Math.max(0, (int) (score * BUCKETS)));
    }

    /** Per-run averages for a metric, oldest first, ending with the current run. */
    private List<Double> trend(String metric) {
        List<Double> points = new ArrayList<>();
        for (HistoryEntry h : timeline) {
            Double v = h.metricAverages() == null ? null : h.metricAverages().get(metric);
            if (v != null) {
                points.add(v);
            }
        }
        return points;
    }

    private void buildGroups() {
        Map<String, TestGroup> byTest = new LinkedHashMap<>();
        for (int i = 0; i < records.size(); i++) {
            EvalRecord r = records.get(i);
            byTest.computeIfAbsent(
                            r.suite() + "\u0000" + r.testName(),
                            k -> new TestGroup(r.suite(), r.testName(), new ArrayList<>()))
                    .recordIndexes()
                    .add(i);
        }
        groups.addAll(byTest.values());
    }

    private void buildChanges() {
        if (previous == null || previous.caseScores() == null) {
            return;
        }
        Map<String, Double> before = previous.caseScores();
        Map<String, Double> now = caseScores(records);
        Map<String, Boolean> seen = new HashMap<>();
        for (int i = 0; i < records.size(); i++) {
            EvalRecord r = records.get(i);
            String key = caseKey(r);
            if (seen.put(key, true) != null) {
                continue; // repeated case in one run: link the first occurrence, score is averaged
            }
            Double was = before.get(key);
            if (was == null) {
                newCases++;
                continue;
            }
            double current = now.get(key);
            CaseChange change = new CaseChange(i, was, current);
            boolean wasPassing = was >= r.threshold();
            boolean nowPassing = current >= r.threshold();
            if (wasPassing && !nowPassing) {
                newFailures.add(change);
            } else if (!wasPassing && nowPassing) {
                fixed.add(change);
            } else if (change.delta() <= -CHANGE_EPSILON) {
                worse.add(change);
            } else if (change.delta() >= CHANGE_EPSILON) {
                better.add(change);
            }
        }
    }

    boolean hasComparison() {
        return previous != null && previous.caseScores() != null;
    }

    /** True if any metric fell more than {@link #CHANGE_EPSILON} below its baseline. */
    boolean hasRegression() {
        return metrics.values().stream().anyMatch(MetricStats::regressed);
    }

    long failedTests() {
        return run.tests() == null ? 0 : run.tests().stream().filter(t -> !t.passed()).count();
    }
}
