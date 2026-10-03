package io.github.llm4j.eval.report;

import java.util.List;
import java.util.Locale;

/**
 * A compact Markdown digest of a run, sized for a pull-request comment or the GitHub Actions job
 * summary ({@code cat eval4j-summary.md >> "$GITHUB_STEP_SUMMARY"}).
 */
final class MarkdownSummary {

    private static final int MAX_FAILURES = 25;

    private MarkdownSummary() {}

    static String render(ReportAnalysis a) {
        StringBuilder sb = new StringBuilder();
        String icon = a.total > 0 && a.passed == a.total && a.failedTests() == 0
                ? (a.hasRegression() ? "⚠️" : "✅")
                : "❌";
        sb.append("## ").append(icon).append(" eval4j report\n\n");
        sb.append(
                String.format(
                        Locale.ROOT,
                        "**%d / %d evaluations passed (%.0f%%)** · average score %.3f",
                        a.passed,
                        a.total,
                        100.0 * a.passRate,
                        a.averageScore));
        if (a.previous != null && a.previous.passRate() != null) {
            sb.append(
                    String.format(
                            Locale.ROOT,
                            " · pass rate %+.1f pts vs previous run",
                            100.0 * (a.passRate - a.previous.passRate())));
        }
        sb.append("\n\n");
        if (a.run.gitSha() != null) {
            sb.append("Commit `").append(Charts.shortSha(a.run.gitSha())).append("` · ");
        }
        sb.append("run `").append(a.run.runId()).append("`\n\n");

        if (!a.metrics.isEmpty()) {
            sb.append("| Metric | Average | Min | Pass rate | Δ baseline | Δ previous |\n");
            sb.append("|---|---:|---:|---:|---:|---:|\n");
            for (ReportAnalysis.MetricStats m : a.metrics.values()) {
                sb.append("| ")
                        .append(cell(m.metric()))
                        .append(m.regressed() ? " ⚠️" : "")
                        .append(String.format(Locale.ROOT, " | %.3f | %.3f | %d/%d | ", m.average(), m.min(), m.passed(), m.count()))
                        .append(delta(m.baselineDelta()))
                        .append(" | ")
                        .append(delta(m.previousDelta()))
                        .append(" |\n");
            }
            sb.append('\n');
        }
        if (a.hasComparison()) {
            sb.append(
                    String.format(
                            Locale.ROOT,
                            "**Since previous run:** %d newly failing · %d fixed · %d dropped · %d improved · %d new%n%n",
                            a.newFailures.size(),
                            a.fixed.size(),
                            a.worse.size(),
                            a.better.size(),
                            a.newCases));
        }
        List<EvalRecord> failures = a.records.stream().filter(r -> !r.passed()).toList();
        if (!failures.isEmpty()) {
            sb.append("<details><summary>").append(failures.size()).append(" failing evaluation(s)</summary>\n\n");
            sb.append("| Test | Metric | Score | Threshold | Reason |\n|---|---|---:|---:|---|\n");
            failures.stream()
                    .limit(MAX_FAILURES)
                    .forEach(
                            r ->
                                    sb.append("| ")
                                            .append(cell(HtmlDashboard.simpleName(r.suite()) + " · " + r.testName()))
                                            .append(" | ")
                                            .append(cell(r.metric()))
                                            .append(String.format(Locale.ROOT, " | %.3f | %.2f | ", r.score(), r.threshold()))
                                            .append(cell(truncate(r.reason(), 240)))
                                            .append(" |\n"));
            if (failures.size() > MAX_FAILURES) {
                sb.append("\n_… and ").append(failures.size() - MAX_FAILURES).append(" more in the full report._\n");
            }
            sb.append("\n</details>\n");
        }
        return sb.toString();
    }

    private static String delta(Double d) {
        return d == null ? "–" : String.format(Locale.ROOT, "%+.3f", d);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** Escapes a value for a Markdown table cell: pipes, newlines and HTML. */
    static String cell(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("`", "\\`")
                .replaceAll("\\s*\\R\\s*", " ");
    }
}
