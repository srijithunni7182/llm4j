package io.github.llm4j.evalreport.analysis;

import io.github.llm4j.evalreport.format.model.Ev;
import io.github.llm4j.evalreport.model.ReportModel.Rollup;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Counts, rates and histograms over sets of evaluations (spec 03 §5.2-5.3). */
final class Rollups {

    private Rollups() {}

    static Rollup of(Collection<Ev> evs) {
        int passed = 0;
        int failed = 0;
        int notEvaluated = 0;
        int errors = 0;
        double scoreSum = 0;
        int scored = 0;
        Map<String, Integer> bySource = new LinkedHashMap<>();
        int[][] hist = new int[10][2];
        for (Ev e : evs) {
            switch (e.status() == null ? "EVALUATED" : e.status()) {
                case "NOT_EVALUATED" -> notEvaluated++;
                case "ERROR" -> errors++;
                default -> {
                    if (!e.counted()) {
                        errors++;
                        continue;
                    }
                    if (e.passed()) {
                        passed++;
                    } else {
                        failed++;
                    }
                    bySource.merge(e.sourceOrFresh(), 1, Integer::sum);
                    if (e.score() != null) {
                        scoreSum += e.score();
                        scored++;
                        int bucket = Math.min(9, (int) Math.floor(e.score() * 10));
                        hist[bucket][e.passed() ? 0 : 1]++;
                    }
                }
            }
        }
        return new Rollup(
                passed,
                failed,
                notEvaluated,
                errors,
                rate(passed, failed),
                scored == 0 ? null : scoreSum / scored,
                bySource,
                hist);
    }

    /** Percentage, or null when nothing was counted. */
    static Double rate(int passed, int failed) {
        int n = passed + failed;
        return n == 0 ? null : 100.0 * passed / n;
    }
}
