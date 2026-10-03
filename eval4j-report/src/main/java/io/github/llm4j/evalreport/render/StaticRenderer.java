package io.github.llm4j.evalreport.render;

import io.github.llm4j.evalreport.format.model.Ev;
import io.github.llm4j.evalreport.model.ReportModel;
import io.github.llm4j.evalreport.model.ReportModel.CaseView;
import io.github.llm4j.evalreport.model.ReportModel.ChangedCase;
import io.github.llm4j.evalreport.model.ReportModel.CompareModel;
import io.github.llm4j.evalreport.model.ReportModel.DimensionView;
import io.github.llm4j.evalreport.model.ReportModel.Rollup;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The static edition: server-rendered HTML with no script and no inline style, so it displays under
 * a strict Content-Security-Policy such as Jenkins' default for archived reports. Every dynamic
 * string is escaped. It links one stylesheet, {@code eval4j-static.css}, written beside it. Passing
 * cases are summarised as counts; failing cases carry full detail.
 */
public final class StaticRenderer {

    public static final String CSS_NAME = "eval4j-static.css";

    private StaticRenderer() {}

    public static String css() {
        try (InputStream in = StaticRenderer.class.getResourceAsStream(CSS_NAME)) {
            if (in == null) {
                throw new IOException("missing resource " + CSS_NAME);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String pct(Double d) {
        return d == null ? "No results" : String.format(Locale.ROOT, "%.1f%%", d);
    }

    public static String render(ReportModel m) {
        StringBuilder h = new StringBuilder(16384);
        String title =
                "eval4j report"
                        + (m.meta().project() == null ? "" : " \u00b7 " + m.meta().project());
        h.append("<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>")
                .append(Escape.html(title))
                .append("</title>")
                .append("<link rel=\"stylesheet\" href=\"")
                .append(CSS_NAME)
                .append("\"></head><body><div class=\"page\">\n");
        h.append("<h1>")
                .append(
                        Escape.html(
                                m.meta().project() == null
                                        ? "Evaluation report"
                                        : m.meta().project()))
                .append("</h1>");
        h.append("<p class=\"sub\">Run ")
                .append(Escape.html(m.meta().runId()))
                .append(
                        m.meta().branch() == null
                                ? ""
                                : " \u00b7 " + Escape.html(m.meta().branch()))
                .append(
                        m.meta().commit() == null
                                ? ""
                                : " \u00b7 " + Escape.html(m.meta().commit()))
                .append(
                        m.meta().startedAt() == null
                                ? ""
                                : " \u00b7 " + Escape.html(m.meta().startedAt()))
                .append("</p>");
        h.append(
                "<nav class=\"toc\"><a href=\"#summary\">Summary</a><a href=\"#dimensions\">Dimensions</a><a href=\"#failures\">Failing cases</a>");
        if (m.compare() != null) {
            h.append("<a href=\"#compare\">Compare</a>");
        }
        h.append(
                "<a href=\"#coverage\">Coverage</a><a href=\"#cost\">Cost</a><a href=\"#models\">Models</a></nav>\n");

        h.append("<h2 id=\"summary\">Summary</h2><div class=\"card\"><div class=\"big num\">")
                .append(pct(m.weighted().rate() != null ? m.weighted().rate() : m.overall().rate()))
                .append("</div>");
        Rollup o = m.overall();
        h.append("<p>")
                .append(m.weighted().rate() != null ? "Priority-weighted pass rate. " : "")
                .append(o.passed() + o.failed())
                .append(" evaluations: ")
                .append(o.passed())
                .append(" passed, ")
                .append(o.failed())
                .append(" failed");
        if (o.notEvaluated() > 0) {
            h.append(", ").append(o.notEvaluated()).append(" not evaluated");
        }
        h.append(
                ".</p><p class=\"hint\">This report informs the release decision. It does not make it.</p></div>\n");
        m.notes()
                .forEach(
                        n ->
                                h.append("<div class=\"note\">")
                                        .append(Escape.html(n.message()))
                                        .append("</div>"));

        h.append("<h2 id=\"dimensions\">Quality dimensions</h2><div class=\"grid\">");
        for (DimensionView d : m.dimensions()) {
            h.append("<div class=\"card\"><h3>")
                    .append(Escape.html(d.name()))
                    .append("</h3>")
                    .append(donut(d))
                    .append("<p class=\"hint\">")
                    .append(
                            d.rollup().rate() == null
                                    ? Escape.html(
                                            d.coverage()
                                                    .state()
                                                    .toLowerCase(Locale.ROOT)
                                                    .replace('_', ' '))
                                    : d.rollup().passed()
                                            + " passed, "
                                            + d.rollup().failed()
                                            + " failed. Goal "
                                            + String.format(Locale.ROOT, "%.0f%%", d.goal()))
                    .append("</p></div>");
        }
        h.append("</div>\n");

        if (m.breakdowns() != null) {
            for (io.github.llm4j.evalreport.model.ReportModel.BreakdownView b : m.breakdowns()) {
                h.append("<h2>")
                        .append(Escape.html(b.name()))
                        .append(" by dimension</h2><table><thead><tr><th>")
                        .append(Escape.html(b.key()))
                        .append("</th><th>Overall</th>");
                for (io.github.llm4j.evalreport.model.ReportModel.BreakdownColumn c : b.columns()) {
                    h.append("<th>").append(Escape.html(c.name())).append("</th>");
                }
                h.append("</tr></thead><tbody>");
                for (io.github.llm4j.evalreport.model.ReportModel.BreakdownRow r : b.rows()) {
                    h.append("<tr><td><b>")
                            .append(Escape.html(r.value()))
                            .append("</b></td><td>")
                            .append(
                                    r.overall().rate() == null
                                            ? "-"
                                            : String.format(
                                                    Locale.ROOT, "%.0f%%", r.overall().rate()))
                            .append("</td>");
                    for (io.github.llm4j.evalreport.model.ReportModel.BreakdownCell c : r.cells()) {
                        int n = c.passed() + c.failed();
                        h.append("<td>")
                                .append(
                                        n == 0
                                                ? "-"
                                                : String.format(
                                                        Locale.ROOT,
                                                        "%.0f%% (%d/%d)",
                                                        c.rate(),
                                                        c.passed(),
                                                        n))
                                .append("</td>");
                    }
                    h.append("</tr>");
                }
                h.append("</tbody></table>\n");
            }
        }

        h.append("<h2 id=\"failures\">Failing cases</h2>");
        int shown = 0;
        for (CaseView c : m.cases()) {
            if (!"FAILED".equals(c.outcome())) {
                continue;
            }
            shown++;
            h.append("<details><summary>").append(Escape.html(c.name())).append("</summary>");
            if (c.input() != null) {
                h.append("<p class=\"hint\">Input</p><pre>")
                        .append(Escape.html(c.input()))
                        .append("</pre>");
            }
            for (Ev e : c.evaluations()) {
                h.append("<p><b>")
                        .append(Escape.html(e.metric()))
                        .append("</b> ")
                        .append(
                                e.counted()
                                        ? (e.passed()
                                                ? "<span class=\"pill good\">Passed</span>"
                                                : "<span class=\"pill crit\">Failed</span>")
                                        : "<span class=\"pill warn\">"
                                                + Escape.html(e.status())
                                                + "</span>")
                        .append(
                                e.score() == null
                                        ? ""
                                        : String.format(Locale.ROOT, " score %.2f", e.score()))
                        .append("</p>");
                if (e.reason() != null) {
                    h.append("<pre>").append(Escape.html(e.reason())).append("</pre>");
                }
                if (e.actualOutput() != null) {
                    h.append("<p class=\"hint\">Actual output</p><pre>")
                            .append(Escape.html(e.actualOutput()))
                            .append("</pre>");
                }
            }
            h.append("</details>");
        }
        if (shown == 0) {
            h.append("<p>No case failed.</p>");
        }
        long passing = m.cases().stream().filter(c -> "PASSED".equals(c.outcome())).count();
        h.append("<p class=\"hint\">")
                .append(passing)
                .append(
                        " passing case(s) are not listed here; the interactive edition shows them.</p>\n");

        java.util.Map<String, int[]> ab = new java.util.LinkedHashMap<>();
        for (CaseView cv : m.cases()) {
            for (Ev e : cv.evaluations()) {
                if ("PAIRWISE".equals(e.kind()) && e.counted() && e.score() != null) {
                    int[] w =
                            ab.computeIfAbsent(
                                    m.metricNames().getOrDefault(e.metric(), e.metric()),
                                    k -> new int[3]);
                    w[e.score() >= 0.99 ? 2 : e.score() > 0.01 ? 1 : 0]++;
                }
            }
        }
        if (!ab.isEmpty()) {
            h.append(
                    "<h2 id=\"ab\">Prompt A/B</h2><table><thead><tr><th>Comparison</th><th>A wins</th><th>Ties</th><th>B wins</th></tr></thead><tbody>");
            ab.forEach(
                    (k, w) ->
                            h.append("<tr><td>")
                                    .append(Escape.html(k))
                                    .append("</td><td class=\"num\">")
                                    .append(w[0])
                                    .append("</td><td class=\"num\">")
                                    .append(w[1])
                                    .append("</td><td class=\"num\">")
                                    .append(w[2])
                                    .append("</td></tr>"));
            h.append("</tbody></table>");
        }

        CompareModel c = m.compare();
        if (c != null) {
            h.append("<h2 id=\"compare\">Compared with the baseline</h2><p class=\"sub\">")
                    .append(Escape.html(c.baselineLabel()))
                    .append(" \u00b7 ")
                    .append(Escape.html(c.baselineRun()))
                    .append("</p>");
            h.append("<p>Pass rate ")
                    .append(pct(c.overallBaseRate()))
                    .append(" \u2192 ")
                    .append(pct(c.overallCandRate()))
                    .append(". ")
                    .append(c.worse())
                    .append(" worse, ")
                    .append(c.better())
                    .append(" better, ")
                    .append(c.same())
                    .append(" unchanged; ")
                    .append(c.withinNoise())
                    .append(" within judge noise (\u00b1")
                    .append(c.noiseBand())
                    .append("). ")
                    .append(c.added())
                    .append(" new, ")
                    .append(c.removed())
                    .append(" removed, ")
                    .append(c.notComparable())
                    .append(" not comparable.</p>");
            c.notes()
                    .forEach(
                            n ->
                                    h.append("<div class=\"note\">")
                                            .append(Escape.html(n.message()))
                                            .append("</div>"));
            h.append(
                    "<table><thead><tr><th>Setting</th><th>Baseline</th><th>Candidate</th></tr></thead><tbody>");
            c.env()
                    .forEach(
                            r ->
                                    h.append(r.changed() ? "<tr class=\"changed\">" : "<tr>")
                                            .append("<td>")
                                            .append(Escape.html(r.field()))
                                            .append("</td><td>")
                                            .append(Escape.html(r.baseline()))
                                            .append("</td><td>")
                                            .append(Escape.html(r.candidate()))
                                            .append("</td></tr>"));
            h.append("</tbody></table>");
            if (!c.changed().isEmpty()) {
                h.append(
                        "<table><thead><tr><th>Case</th><th>Metric</th><th>Change</th><th>Baseline</th><th>Now</th></tr></thead><tbody>");
                for (ChangedCase x : c.changed()) {
                    h.append("<tr><td>")
                            .append(Escape.html(x.caseName()))
                            .append("</td><td>")
                            .append(Escape.html(x.metric()))
                            .append("</td><td>")
                            .append("WORSE".equals(x.change()) ? "Worse" : "Better")
                            .append(x.withinNoise() ? " (within noise)" : "")
                            .append("</td><td class=\"num\">")
                            .append(
                                    x.baseScore() == null
                                            ? ""
                                            : String.format(Locale.ROOT, "%.2f", x.baseScore()))
                            .append("</td><td class=\"num\">")
                            .append(
                                    x.candScore() == null
                                            ? ""
                                            : String.format(Locale.ROOT, "%.2f", x.candScore()))
                            .append("</td></tr>");
                }
                h.append("</tbody></table>");
            }
        }

        h.append(
                "<h2 id=\"coverage\">Golden dataset coverage</h2><table><thead><tr><th>Dimension</th><th>State</th><th>Declared</th><th>Evaluated</th></tr></thead><tbody>");
        for (DimensionView d : m.dimensions()) {
            h.append("<tr><td>")
                    .append(Escape.html(d.name()))
                    .append("</td><td>")
                    .append(
                            Escape.html(
                                    d.coverage()
                                            .state()
                                            .toLowerCase(Locale.ROOT)
                                            .replace('_', ' ')))
                    .append("</td><td class=\"num\">")
                    .append(d.coverage().declared())
                    .append("</td><td class=\"num\">")
                    .append(d.coverage().evaluated())
                    .append("</td></tr>");
        }
        h.append("</tbody></table>");

        var e = m.evidence();
        h.append("<h2 id=\"cost\">Cost and evidence</h2><p>Profile ")
                .append(Escape.html(e.profile()))
                .append(". ")
                .append(e.fresh())
                .append(" evaluated now, ")
                .append(e.reused())
                .append(" reused from cache, ")
                .append(e.carried())
                .append(" carried from earlier runs.")
                .append(
                        e.costUsd() == null
                                ? " No cost recorded."
                                : String.format(Locale.ROOT, " Judge spend $%.4f.", e.costUsd()))
                .append("</p>");

        h.append("<h2 id=\"models\">Judges and models</h2>");
        var env = m.meta().env();
        h.append(
                "<table><thead><tr><th>Kind</th><th>Id</th><th>Model</th><th>Detail</th></tr></thead><tbody>");
        env.path("judges")
                .forEach(
                        j ->
                                h.append("<tr><td>Judge</td><td>")
                                        .append(Escape.html(j.path("id").asText()))
                                        .append("</td><td>")
                                        .append(Escape.html(j.path("model").asText("")))
                                        .append("</td><td>")
                                        .append(
                                                Escape.html(
                                                        "calls "
                                                                + j.path("stats")
                                                                        .path("calls")
                                                                        .asText("-")))
                                        .append("</td></tr>"));
        env.path("agents")
                .forEach(
                        a ->
                                h.append("<tr><td>Agent</td><td>")
                                        .append(Escape.html(a.path("id").asText()))
                                        .append("</td><td>")
                                        .append(Escape.html(a.path("model").asText("")))
                                        .append("</td><td>")
                                        .append(Escape.html(a.path("promptVersion").asText("")))
                                        .append("</td></tr>"));
        h.append("</tbody></table>");
        h.append(
                "<footer>Free, open-source evaluation for AI agents, from llm4j. Static edition: no script, no network, no telemetry.</footer></div></body></html>\n");
        return h.toString();
    }

    /** A ring chart in SVG using attributes only (no style attribute). */
    private static String donut(DimensionView d) {
        Rollup r = d.rollup();
        double radius = 42;
        double circ = 2 * Math.PI * radius;
        StringBuilder s = new StringBuilder();
        s.append(
                        "<svg width=\"110\" height=\"110\" viewBox=\"0 0 110 110\" role=\"img\" aria-label=\"")
                .append(Escape.html(d.name()))
                .append(' ')
                .append(Escape.html(pct(r.rate())))
                .append("\">");
        s.append(
                "<circle cx=\"55\" cy=\"55\" r=\"42\" fill=\"none\" stroke=\"#8a92a5\" stroke-opacity=\".25\" stroke-width=\"11\"/>");
        int total = r.passed() + r.failed();
        if (total > 0) {
            double pass = circ * r.passed() / total;
            s.append(
                    "<circle cx=\"55\" cy=\"55\" r=\"42\" fill=\"none\" stroke=\"#d03b3b\" stroke-width=\"11\" transform=\"rotate(-90 55 55)\"/>");
            if (r.passed() > 0) {
                s.append(
                        String.format(
                                Locale.ROOT,
                                "<circle cx=\"55\" cy=\"55\" r=\"42\" fill=\"none\" stroke=\"#2a78d6\" stroke-width=\"11\" stroke-dasharray=\"%.2f %.2f\" transform=\"rotate(-90 55 55)\"/>",
                                pass,
                                circ));
            }
        }
        s.append("<text x=\"55\" y=\"60\" text-anchor=\"middle\" font-size=\"")
                .append(total > 0 ? 18 : 11)
                .append("\" font-weight=\"700\">")
                .append(total > 0 ? String.format(Locale.ROOT, "%.0f%%", r.rate()) : "No results")
                .append("</text></svg>");
        return s.toString();
    }
}
