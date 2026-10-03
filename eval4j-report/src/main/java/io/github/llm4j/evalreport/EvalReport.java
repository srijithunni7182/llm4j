package io.github.llm4j.evalreport;

import io.github.llm4j.evalreport.analysis.Analyzer;
import io.github.llm4j.evalreport.analysis.Baselines;
import io.github.llm4j.evalreport.analysis.Compare;
import io.github.llm4j.evalreport.config.ReportConfig;
import io.github.llm4j.evalreport.format.RunBundle;
import io.github.llm4j.evalreport.format.RunStore;
import io.github.llm4j.evalreport.format.model.Ev;
import io.github.llm4j.evalreport.format.model.RunMeta;
import io.github.llm4j.evalreport.model.ReportModel;
import io.github.llm4j.evalreport.model.ReportModel.CompareModel;
import io.github.llm4j.evalreport.model.ReportModel.TrendPoint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Public façade: load a run, analyse it, compare it with its baseline. */
public final class EvalReport {

    private static final int TREND_RUNS = 40;

    private EvalReport() {}

    /**
     * Builds the report model for {@code runId} (the newest run when null), with a comparison
     * against {@code baselineId} (chosen by policy when null; none when {@code compare} is false).
     */
    public static ReportModel build(
            RunStore store, String runId, String baselineId, boolean compare, ReportConfig config) {
        List<RunMeta> runs = store.list();
        if (runs.isEmpty()) {
            throw new IllegalArgumentException("no runs found under " + store.root());
        }
        RunMeta candidateMeta = runId == null ? runs.get(runs.size() - 1) : find(runs, runId);
        RunBundle cand = store.load(candidateMeta.runId(), false);
        ReportModel model = Analyzer.analyze(cand, config, Instant.now().toString());
        CompareModel cm = null;
        if (compare) {
            Baselines.Pick pick = Baselines.select(candidateMeta, runs, config, baselineId);
            if (pick != null) {
                RunBundle base = store.load(pick.run().runId(), false);
                cm = Compare.compare(base, cand, config, pick.label());
            }
        }
        List<TrendPoint> trend = trend(store, runs, candidateMeta);
        return new ReportModel(
                model.meta(),
                model.overall(),
                model.weighted(),
                model.families(),
                model.dimensions(),
                model.cases(),
                model.evidence(),
                model.notes(),
                cm,
                trend,
                model.traces(),
                model.optimizations(),
                model.tests());
    }

    private static RunMeta find(List<RunMeta> runs, String id) {
        for (RunMeta r : runs) {
            if (id.equals(r.runId())) {
                return r;
            }
        }
        throw new IllegalArgumentException("no run with id " + id);
    }

    private static List<TrendPoint> trend(RunStore store, List<RunMeta> runs, RunMeta candidate) {
        List<RunMeta> same = new ArrayList<>();
        for (RunMeta r : runs) {
            boolean sameBranch =
                    candidate.branch() == null
                            ? r.branch() == null
                            : candidate.branch().equals(r.branch());
            boolean done = "COMPLETE".equals(r.status()) || "PARTIAL".equals(r.status());
            boolean notAfter =
                    r.startedAt() != null
                            && candidate.startedAt() != null
                            && r.startedAt().compareTo(candidate.startedAt()) <= 0;
            if (sameBranch && done && notAfter) {
                same.add(r);
            }
        }
        if (same.size() > TREND_RUNS) {
            same = same.subList(same.size() - TREND_RUNS, same.size());
        }
        List<TrendPoint> points = new ArrayList<>();
        for (RunMeta r : same) {
            int p = 0;
            int f = 0;
            for (Ev e : store.load(r.runId(), false).evaluations()) {
                if (e.counted()) {
                    if (e.passed()) {
                        p++;
                    } else {
                        f++;
                    }
                }
            }
            Double rate = p + f == 0 ? null : 100.0 * p / (p + f);
            points.add(
                    new TrendPoint(r.runId(), r.startedAt(), r.commit(), rate, java.util.Map.of()));
        }
        return points;
    }
}
