package io.github.llm4j.evalreport.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.evalreport.format.model.Ev;
import java.util.List;
import java.util.Map;

/**
 * The analysed, renderer-facing model. Every number shown anywhere is computed once into this
 * model; renderers only format it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReportModel(
        Meta meta,
        Rollup overall,
        WeightedRate weighted,
        List<FamilyView> families,
        List<DimensionView> dimensions,
        List<CaseView> cases,
        Evidence evidence,
        List<Note> notes,
        CompareModel compare,
        List<TrendPoint> trend,
        List<JsonNode> traces,
        List<JsonNode> optimizations,
        List<TestRow> tests,
        List<JudgeReliability> reliability,
        Map<String, Map<String, String>> presets,
        Branding branding,
        Map<String, String> metricNames,
        List<BreakdownView> breakdowns) {

    /**
     * Results broken down by a scenario tag (for example {@code agent}): one row per tag value, one
     * column per quality dimension, so "which agent scored how much on what" is one table.
     */
    public record BreakdownView(
            String key, String name, List<BreakdownColumn> columns, List<BreakdownRow> rows) {}

    public record BreakdownColumn(String dimension, String name, double goal) {}

    /**
     * {@code caseIds} are the {@link CaseView#caseId()} values of this row's cases, for the
     * drill-down.
     */
    public record BreakdownRow(
            String value, Rollup overall, List<BreakdownCell> cells, List<String> caseIds) {}

    /**
     * {@code status} is MET or BELOW the dimension's goal, or NONE when this row has no evaluation
     * there.
     */
    public record BreakdownCell(
            String dimension,
            int passed,
            int failed,
            Double rate,
            String status,
            List<String> failedCases) {}

    /** Run facts plus how the report was made. */
    public record Meta(
            String project,
            String generatedAt,
            String runId,
            String runStatus,
            Integer runNumber,
            String startedAt,
            String endedAt,
            String branch,
            String commit,
            JsonNode profile,
            JsonNode env,
            JsonNode summary,
            double noiseBand,
            double warnGap) {}

    /** Counts and rates over a set of evaluations. */
    public record Rollup(
            int passed,
            int failed,
            int notEvaluated,
            int errors,
            Double rate,
            Double avgScore,
            Map<String, Integer> bySource,
            int[][] histogram) {}

    public record WeightedRate(Double rate, Double goal, int dimensionsCounted) {}

    public record FamilyView(
            String id,
            String name,
            Rollup rollup,
            List<FacetView> facets,
            List<String> dimensions) {}

    public record FacetView(String id, String name, Rollup rollup, List<String> dimensions) {}

    public record Coverage(String state, int declared, int evaluated, List<String> missingCases) {}

    public record MetricView(
            String id,
            String name,
            String kind,
            String family,
            String facet,
            Double threshold,
            String unit,
            Double budget,
            String judgeId,
            Rollup rollup) {}

    public record DimensionView(
            String id,
            String name,
            String blurb,
            double goal,
            String priority,
            int weight,
            Rollup rollup,
            Double gap,
            String status,
            Coverage coverage,
            List<String> families,
            List<MetricView> metrics,
            Map<String, Rollup> byFamily) {}

    /** One scenario or test with every evaluation of it. */
    public record CaseView(
            String caseId,
            String id,
            String name,
            String input,
            List<String> dimensions,
            String family,
            String outcome,
            List<Ev> evaluations,
            Map<String, String> dims) {}

    public record TestRow(
            String testId, String name, String status, Long durationMs, String message) {}

    public record Evidence(
            int fresh,
            int reused,
            int carried,
            Double costUsd,
            Double judgeBudgetUsd,
            Double estimatedFullCostUsd,
            Double savedByReuseUsd,
            String profile) {}

    public record Branding(String title, String logo, String accent) {}

    /** How far a judge can be trusted, from this run's own data. */
    public record JudgeReliability(
            String judgeId,
            String model,
            int evaluations,
            int multiSample,
            Double selfConsistency,
            Double meanSpread,
            int calls,
            int failures,
            Double failureRate,
            List<String> sameFamilyAs) {}

    public record Note(String severity, String code, String message) {}

    public record TrendPoint(
            String runId,
            String startedAt,
            String commit,
            Double rate,
            Map<String, Double> byDimension) {}

    // ---- comparison ----

    public record ChangedCase(
            String key,
            String caseId,
            String caseName,
            String metric,
            String dimension,
            String kind,
            String change,
            boolean withinNoise,
            Double baseScore,
            Double candScore,
            Double scoreDelta,
            String baseReason,
            String candReason,
            String baseSource,
            String candSource,
            String baseOutput,
            String candOutput,
            boolean answerUnchanged) {}

    public record DimensionCompare(
            String id,
            String name,
            Double baseRate,
            Double candRate,
            Double delta,
            Double goal,
            int worse,
            int better,
            int withinNoise,
            int matched) {}

    public record EnvRow(String field, String baseline, String candidate, boolean changed) {}

    public record ScatterPoint(String key, Double base, Double cand, String change) {}

    public record CompareModel(
            String baselineRun,
            String baselineLabel,
            String candidateRun,
            String basePicked,
            double noiseBand,
            int matched,
            int worse,
            int better,
            int same,
            int withinNoise,
            int added,
            int removed,
            int notComparable,
            Double overallBaseRate,
            Double overallCandRate,
            List<DimensionCompare> dimensions,
            List<ChangedCase> changed,
            List<EnvRow> env,
            List<ScatterPoint> scatter,
            List<Note> notes) {}
}
