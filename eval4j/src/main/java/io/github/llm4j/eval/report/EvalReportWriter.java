package io.github.llm4j.eval.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes a run's records as {@code eval4j-report.json} and a single self-contained {@code
 * eval4j-report.html} (inline CSS/SVG, no external requests). Every dynamic value is HTML-escaped.
 */
public final class EvalReportWriter {

    /** Run-level metadata plus all records; the JSON report's shape. */
    public record RunInfo(
            String runId,
            String startedAt,
            String endedAt,
            String gitSha,
            List<EvalRecord> records) {}

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private EvalReportWriter() {}

    /**
     * @param history prior runs (oldest first) for trend sparklines; may be empty
     * @param baselineAverages per-metric baseline averages for a delta column; may be empty
     */
    public static void write(
            Path dir,
            RunInfo run,
            List<HistoryEntry> history,
            Map<String, Double> baselineAverages) {
        try {
            AtomicFiles.write(dir.resolve("eval4j-report.json"), MAPPER.writeValueAsBytes(run));
            AtomicFiles.write(
                    dir.resolve("eval4j-report.html"),
                    html(run, history, baselineAverages).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Could not write eval4j report to " + dir, e);
        }
    }

    /** Per-metric average score of the given records. */
    public static Map<String, Double> metricAverages(List<EvalRecord> records) {
        Map<String, double[]> acc = new TreeMap<>();
        for (EvalRecord r : records) {
            double[] a = acc.computeIfAbsent(r.metric(), k -> new double[2]);
            a[0] += r.score();
            a[1]++;
        }
        Map<String, Double> out = new TreeMap<>();
        acc.forEach((k, a) -> out.put(k, a[0] / a[1]));
        return out;
    }

    static String html(
            RunInfo run, List<HistoryEntry> history, Map<String, Double> baselineAverages) {
        List<EvalRecord> records = run.records();
        long passed = records.stream().filter(EvalRecord::passed).count();
        StringBuilder sb = new StringBuilder(8192);
        sb.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<title>eval4j report</title><style>")
                .append("body{font:14px system-ui,sans-serif;margin:24px;color:#1b1f23}")
                .append("h1{font-size:20px}h2{font-size:16px;margin-top:28px}")
                .append(".cards{display:flex;gap:12px;flex-wrap:wrap}")
                .append(".card{border:1px solid #d0d7de;border-radius:8px;padding:10px 16px}")
                .append(".card b{display:block;font-size:22px}")
                .append("table{border-collapse:collapse;width:100%}")
                .append("th,td{border-bottom:1px solid #d0d7de;padding:6px 8px;text-align:left;")
                .append("vertical-align:top}th{cursor:pointer;background:#f6f8fa}")
                .append(".fail{color:#b00020}.pass{color:#1a7f37}.reg{background:#ffebe9}")
                .append("details summary{cursor:pointer}</style></head><body>");
        sb.append("<h1>eval4j report</h1><p>run <code>")
                .append(esc(run.runId()))
                .append("</code> · ")
                .append(esc(run.startedAt()))
                .append(
                        run.gitSha() == null
                                ? ""
                                : " · commit <code>" + esc(run.gitSha()) + "</code>")
                .append("</p><div class=\"cards\">");
        card(sb, "Evaluations", String.valueOf(records.size()));
        card(sb, "Passed", passed + " / " + records.size());
        card(
                sb,
                "Pass rate",
                records.isEmpty()
                        ? "n/a"
                        : String.format(
                                java.util.Locale.ROOT, "%.0f%%", 100.0 * passed / records.size()));
        sb.append("</div>");

        sb.append(
                        "<h2>Metrics</h2><table id=\"metrics\"><thead><tr><th>Metric</th><th>Average</th>")
                .append(
                        "<th>Min</th><th>Pass rate</th><th>Baseline Δ</th><th>Trend</th></tr></thead><tbody>");
        Map<String, Double> averages = metricAverages(records);
        for (String metric : averages.keySet()) {
            List<EvalRecord> mine =
                    records.stream().filter(r -> r.metric().equals(metric)).toList();
            double min = mine.stream().mapToDouble(EvalRecord::score).min().orElse(0);
            long ok = mine.stream().filter(EvalRecord::passed).count();
            Double base = baselineAverages.get(metric);
            String delta = "–";
            String cls = "";
            if (base != null) {
                double d = averages.get(metric) - base;
                delta = String.format(java.util.Locale.ROOT, "%+.3f", d);
                cls = d < -0.05 ? " class=\"reg\"" : "";
            }
            sb.append("<tr")
                    .append(cls)
                    .append("><td>")
                    .append(esc(metric))
                    .append("</td><td>")
                    .append(String.format(java.util.Locale.ROOT, "%.3f", averages.get(metric)))
                    .append("</td><td>")
                    .append(String.format(java.util.Locale.ROOT, "%.3f", min))
                    .append("</td><td>")
                    .append(ok)
                    .append('/')
                    .append(mine.size())
                    .append("</td><td>")
                    .append(delta)
                    .append("</td><td>")
                    .append(sparkline(metric, history, averages.get(metric)))
                    .append("</td></tr>");
        }
        sb.append("</tbody></table>");

        sb.append(
                        "<h2>Evaluations</h2><table id=\"records\"><thead><tr><th>Result</th><th>Suite</th>")
                .append(
                        "<th>Test</th><th>Metric</th><th>Score</th><th>Threshold</th><th>Reason</th>")
                .append("</tr></thead><tbody>");
        for (EvalRecord r : records) {
            sb.append("<tr><td class=\"")
                    .append(r.passed() ? "pass\">PASS" : "fail\">FAIL")
                    .append("</td><td>")
                    .append(esc(r.suite()))
                    .append("</td><td>")
                    .append(esc(r.testName()))
                    .append("</td><td>")
                    .append(esc(r.metric()))
                    .append("</td><td>")
                    .append(String.format(java.util.Locale.ROOT, "%.3f", r.score()))
                    .append("</td><td>")
                    .append(String.format(java.util.Locale.ROOT, "%.3f", r.threshold()))
                    .append("</td><td>");
            if (r.passed()) {
                sb.append(esc(r.reason()));
            } else {
                sb.append("<details open><summary>why it failed</summary>")
                        .append(esc(r.reason()))
                        .append("</details>");
            }
            sb.append("</td></tr>");
        }
        sb.append("</tbody></table>");
        sb.append("<script>document.querySelectorAll('th').forEach(function(th){")
                .append("th.addEventListener('click',function(){var t=th.closest('table'),")
                .append("b=t.tBodies[0],i=Array.prototype.indexOf.call(th.parentNode.children,th),")
                .append("rows=Array.prototype.slice.call(b.rows),asc=th.dataset.asc!=='1';")
                .append(
                        "th.dataset.asc=asc?'1':'0';rows.sort(function(x,y){var a=x.cells[i].textContent,")
                .append("c=y.cells[i].textContent,n=parseFloat(a),m=parseFloat(c);")
                .append("var r=(!isNaN(n)&&!isNaN(m))?n-m:a.localeCompare(c);return asc?r:-r;});")
                .append("rows.forEach(function(r){b.appendChild(r);});});});</script>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private static void card(StringBuilder sb, String label, String value) {
        sb.append("<div class=\"card\">")
                .append(esc(label))
                .append("<b>")
                .append(esc(value))
                .append("</b></div>");
    }

    /** Inline SVG trend of a metric's per-run average; empty unless ≥ 2 runs are known. */
    private static String sparkline(String metric, List<HistoryEntry> history, double current) {
        List<Double> points = new java.util.ArrayList<>();
        for (HistoryEntry h : history) {
            Double v = h.metricAverages() == null ? null : h.metricAverages().get(metric);
            if (v != null) {
                points.add(v);
            }
        }
        if (points.isEmpty() || points.get(points.size() - 1) != current) {
            points.add(current);
        }
        if (points.size() < 2) {
            return "";
        }
        double min = points.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double max = points.stream().mapToDouble(Double::doubleValue).max().orElse(1);
        double span = max - min == 0 ? 1 : max - min;
        StringBuilder sb =
                new StringBuilder("<svg width=\"100\" height=\"24\" viewBox=\"0 0 100 24\">")
                        .append(
                                "<polyline fill=\"none\" stroke=\"#0969da\" stroke-width=\"1.5\" points=\"");
        for (int i = 0; i < points.size(); i++) {
            double x = 100.0 * i / (points.size() - 1);
            double y = 22 - 20 * (points.get(i) - min) / span;
            sb.append(String.format(java.util.Locale.ROOT, "%.1f,%.1f ", x, y));
        }
        return sb.append("\"/></svg>").toString();
    }

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
