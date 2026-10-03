package io.github.llm4j.evalreport.render;

import io.github.llm4j.evalreport.model.ReportModel;
import io.github.llm4j.evalreport.model.ReportModel.ChangedCase;
import io.github.llm4j.evalreport.model.ReportModel.CompareModel;
import io.github.llm4j.evalreport.model.ReportModel.DimensionView;
import java.util.Locale;

/** A plain-text summary for pull-request comments and build logs. No verdict wording. */
public final class MarkdownSummary {

    private MarkdownSummary() {}

    private static String pct(Double d) {
        return d == null ? "no results" : String.format(Locale.ROOT, "%.1f%%", d);
    }

    public static String summary(ReportModel m) {
        StringBuilder sb = new StringBuilder();
        sb.append("## eval4j report");
        if (m.meta().project() != null) {
            sb.append(" \u00b7 ").append(m.meta().project());
        }
        sb.append("\n\nRun `").append(m.meta().runId()).append('`');
        if (m.meta().branch() != null) {
            sb.append(" on `").append(m.meta().branch()).append('`');
        }
        sb.append(". ")
                .append(m.overall().passed() + m.overall().failed())
                .append(" evaluations: ")
                .append(m.overall().passed())
                .append(" passed, ")
                .append(m.overall().failed())
                .append(" failed");
        if (m.overall().notEvaluated() > 0) {
            sb.append(", ").append(m.overall().notEvaluated()).append(" not evaluated");
        }
        sb.append(". Pass rate ").append(pct(m.overall().rate())).append(".\n");
        if (m.weighted().rate() != null) {
            sb.append("\nPriority-weighted pass rate ")
                    .append(pct(m.weighted().rate()))
                    .append(" against a weighted goal of ")
                    .append(pct(m.weighted().goal()))
                    .append(".\n");
        }
        sb.append("\n| Dimension | Pass rate | Goal | Passed | Failed |\n|---|---|---|---|---|\n");
        for (DimensionView d : m.dimensions()) {
            sb.append("| ")
                    .append(cell(d.name()))
                    .append(" | ")
                    .append(pct(d.rollup().rate()))
                    .append(" | ")
                    .append(String.format(Locale.ROOT, "%.0f%%", d.goal()))
                    .append(" | ")
                    .append(d.rollup().passed())
                    .append(" | ")
                    .append(d.rollup().failed())
                    .append(" |\n");
        }
        if (m.breakdowns() != null) {
            for (io.github.llm4j.evalreport.model.ReportModel.BreakdownView b : m.breakdowns()) {
                sb.append("\n### ")
                        .append(cell(b.name()))
                        .append("\n\n| ")
                        .append(cell(b.key()))
                        .append(" | Overall |");
                for (io.github.llm4j.evalreport.model.ReportModel.BreakdownColumn c : b.columns()) {
                    sb.append(' ').append(cell(c.name())).append(" |");
                }
                sb.append("\n|---|---|");
                sb.append("---|".repeat(b.columns().size())).append('\n');
                for (io.github.llm4j.evalreport.model.ReportModel.BreakdownRow r : b.rows()) {
                    sb.append("| ")
                            .append(cell(r.value()))
                            .append(" | ")
                            .append(pct(r.overall().rate()))
                            .append(" |");
                    for (io.github.llm4j.evalreport.model.ReportModel.BreakdownCell c : r.cells()) {
                        int n = c.passed() + c.failed();
                        sb.append(' ')
                                .append(
                                        n == 0
                                                ? "-"
                                                : String.format(
                                                        Locale.ROOT,
                                                        "%.0f%% (%d/%d)",
                                                        c.rate(),
                                                        c.passed(),
                                                        n))
                                .append(" |");
                    }
                    sb.append('\n');
                }
            }
        }
        sb.append("\nThis report informs the release decision; it does not make it.\n");
        return sb.toString();
    }

    public static String compare(ReportModel m, int top) {
        CompareModel c = m.compare();
        if (c == null) {
            return "No baseline run to compare with.\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## Compared with ")
                .append(c.baselineRun())
                .append("\n\n")
                .append(c.baselineLabel())
                .append("\n\n");
        sb.append("Pass rate ")
                .append(pct(c.overallBaseRate()))
                .append(" \u2192 ")
                .append(pct(c.overallCandRate()))
                .append(". Of ")
                .append(c.matched())
                .append(" matched evaluations: ")
                .append(c.worse())
                .append(" worse, ")
                .append(c.better())
                .append(" better, ")
                .append(c.same())
                .append(" unchanged; ")
                .append(c.withinNoise())
                .append(" of the changes are within judge noise (\u00b1")
                .append(c.noiseBand())
                .append("). ")
                .append(c.added())
                .append(" new, ")
                .append(c.removed())
                .append(" removed, ")
                .append(c.notComparable())
                .append(" not comparable.\n\n");
        int n = 0;
        for (ChangedCase x : c.changed()) {
            if (n++ >= top) {
                break;
            }
            sb.append("- ")
                    .append(x.change().toLowerCase(Locale.ROOT))
                    .append(": ")
                    .append(cell(x.caseName()))
                    .append(" \u00b7 ")
                    .append(x.metric())
                    .append(' ')
                    .append(
                            x.baseScore() == null
                                    ? ""
                                    : String.format(Locale.ROOT, "%.2f", x.baseScore()))
                    .append(" \u2192 ")
                    .append(
                            x.candScore() == null
                                    ? ""
                                    : String.format(Locale.ROOT, "%.2f", x.candScore()))
                    .append(x.withinNoise() ? " (within noise)" : "")
                    .append('\n');
        }
        return sb.toString();
    }

    private static String cell(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }
}
