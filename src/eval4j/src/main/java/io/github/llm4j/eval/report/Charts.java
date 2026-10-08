package io.github.llm4j.eval.report;

import java.util.List;
import java.util.Locale;

/**
 * Inline-SVG building blocks for the dashboard. Colours come from CSS classes (so dark mode just
 * works) except the per-metric trend palette, which is fixed colour-blind-safe hues. Nothing here
 * references an external resource.
 */
final class Charts {

    /** Okabe-Ito colour-blind-safe palette, readable on light and dark backgrounds. */
    private static final String[] PALETTE = {
        "#0072b2", "#e69f00", "#009e73", "#cc79a7", "#56b4e9", "#d55e00", "#f0e442", "#999999"
    };

    private Charts() {}

    static String color(int index) {
        return PALETTE[index % PALETTE.length];
    }

    static String num(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    /** A 0..1 score bar with a tick at the pass threshold. */
    static String scoreBar(double score, double threshold) {
        double w = 120;
        double s = Math.max(0, Math.min(1, score)) * w;
        double t = Math.max(0, Math.min(1, threshold)) * w;
        return "<svg class=\"bar\" width=\"120\" height=\"12\" viewBox=\"0 0 120 12\" role=\"img\""
                + " aria-label=\"score "
                + String.format(Locale.ROOT, "%.2f", score)
                + " of 1, threshold "
                + String.format(Locale.ROOT, "%.2f", threshold)
                + "\"><rect class=\"bg\" width=\"120\" height=\"12\" rx=\"3\"/><rect class=\""
                + (score >= threshold ? "bp" : "bf")
                + "\" width=\""
                + num(s)
                + "\" height=\"12\" rx=\"3\"/><line class=\"tick\" x1=\""
                + num(t)
                + "\" x2=\""
                + num(t)
                + "\" y1=\"0\" y2=\"12\"/></svg>";
    }

    /** Ten-bucket score histogram; buckets below the threshold are drawn in the failing colour. */
    static String histogram(int[] buckets, double threshold) {
        int max = 1;
        for (int b : buckets) {
            max = Math.max(max, b);
        }
        StringBuilder sb =
                new StringBuilder(
                        "<svg class=\"hist\" width=\"110\" height=\"30\" viewBox=\"0 0 110 30\""
                                + " role=\"img\" aria-label=\"score distribution\">");
        for (int i = 0; i < buckets.length; i++) {
            double h = buckets[i] == 0 ? 0 : Math.max(2, 28.0 * buckets[i] / max);
            double lo = (double) i / buckets.length;
            sb.append("<rect class=\"")
                    .append(lo + 1e-9 >= threshold ? "bp" : "bf")
                    .append("\" x=\"")
                    .append(num(i * 11.0))
                    .append("\" y=\"")
                    .append(num(29 - h))
                    .append("\" width=\"9\" height=\"")
                    .append(num(h))
                    .append("\"><title>")
                    .append(String.format(Locale.ROOT, "%.1f–%.1f", lo, lo + 0.1))
                    .append(": ")
                    .append(buckets[i])
                    .append("</title></rect>");
        }
        return sb.append("</svg>").toString();
    }

    /** Tiny trend line; empty with fewer than two points. */
    static String sparkline(List<Double> points) {
        if (points.size() < 2) {
            return "";
        }
        double min = points.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double max = points.stream().mapToDouble(Double::doubleValue).max().orElse(1);
        double span = max - min == 0 ? 1 : max - min;
        StringBuilder sb =
                new StringBuilder(
                        "<svg class=\"spark\" width=\"100\" height=\"24\" viewBox=\"0 0 100 24\">"
                                + "<polyline fill=\"none\" stroke=\"#0969da\" stroke-width=\"1.5\""
                                + " points=\"");
        for (int i = 0; i < points.size(); i++) {
            double x = 100.0 * i / (points.size() - 1);
            double y = 22 - 20 * (points.get(i) - min) / span;
            sb.append(String.format(Locale.ROOT, "%.1f,%.1f ", x, y));
        }
        return sb.append("\"/></svg>").toString();
    }

    /**
     * A multi-series line chart over the run timeline: one line per metric average plus the pass
     * rate (dashed). Hovering a point shows run, time and value through native SVG tooltips.
     */
    static String trendChart(List<HistoryEntry> timeline, List<String> metrics) {
        int n = timeline.size();
        double w = 760;
        double h = 230;
        double left = 36;
        double right = 12;
        double top = 10;
        double bottom = 24;
        double pw = w - left - right;
        double ph = h - top - bottom;
        double lo = axisFloor(timeline, metrics);
        StringBuilder sb =
                new StringBuilder(
                        "<svg class=\"trend\" viewBox=\"0 0 760 230\" width=\"100%\""
                                + " role=\"img\" aria-label=\"score trend across runs\">");
        for (int g = 0; g <= 4; g++) {
            double y = top + ph * (1 - g / 4.0);
            sb.append("<line class=\"grid\" x1=\"")
                    .append(num(left))
                    .append("\" x2=\"")
                    .append(num(w - right))
                    .append("\" y1=\"")
                    .append(num(y))
                    .append("\" y2=\"")
                    .append(num(y))
                    .append("\"/><text class=\"axis\" x=\"")
                    .append(num(left - 6))
                    .append("\" y=\"")
                    .append(num(y + 4))
                    .append("\" text-anchor=\"end\">")
                    .append(String.format(Locale.ROOT, "%.2f", lo + (1 - lo) * g / 4.0))
                    .append("</text>");
        }
        sb.append("<text class=\"axis\" x=\"")
                .append(num(left))
                .append("\" y=\"")
                .append(num(h - 6))
                .append("\">oldest</text><text class=\"axis\" x=\"")
                .append(num(w - right))
                .append("\" y=\"")
                .append(num(h - 6))
                .append("\" text-anchor=\"end\">this run</text>");
        int series = 0;
        for (String metric : metrics) {
            series(
                    sb,
                    timeline,
                    metric,
                    entry ->
                            entry.metricAverages() == null
                                    ? null
                                    : entry.metricAverages().get(metric),
                    color(series++),
                    false,
                    left,
                    top,
                    pw,
                    ph,
                    lo);
        }
        series(
                sb,
                timeline,
                "pass rate",
                HistoryEntry::passRate,
                "currentColor",
                true,
                left,
                top,
                pw,
                ph,
                lo);
        return sb.append("</svg>").toString();
    }

    private static void series(
            StringBuilder sb,
            List<HistoryEntry> timeline,
            String name,
            java.util.function.Function<HistoryEntry, Double> value,
            String color,
            boolean dashed,
            double left,
            double top,
            double pw,
            double ph,
            double lo) {
        int n = timeline.size();
        StringBuilder points = new StringBuilder();
        StringBuilder dots = new StringBuilder();
        int count = 0;
        for (int i = 0; i < n; i++) {
            Double v = value.apply(timeline.get(i));
            if (v == null) {
                continue;
            }
            double x = left + (n == 1 ? pw / 2 : pw * i / (n - 1));
            double y = top + ph * (1 - Math.max(0, Math.min(1, (v - lo) / (1 - lo))));
            points.append(num(x)).append(',').append(num(y)).append(' ');
            HistoryEntry e = timeline.get(i);
            dots.append("<circle cx=\"")
                    .append(num(x))
                    .append("\" cy=\"")
                    .append(num(y))
                    .append("\" r=\"3\" fill=\"")
                    .append(color)
                    .append("\"><title>")
                    .append(EvalReportWriter.esc(name))
                    .append(": ")
                    .append(String.format(Locale.ROOT, "%.3f", v))
                    .append(" · ")
                    .append(EvalReportWriter.esc(e.timestamp()))
                    .append(
                            e.gitSha() == null
                                    ? ""
                                    : " · " + EvalReportWriter.esc(shortSha(e.gitSha())))
                    .append("</title></circle>");
            count++;
        }
        if (count == 0) {
            return;
        }
        if (count > 1) {
            sb.append("<polyline fill=\"none\" stroke=\"")
                    .append(color)
                    .append("\" stroke-width=\"2\"")
                    .append(dashed ? " stroke-dasharray=\"5 4\" opacity=\"0.7\"" : "")
                    .append(" points=\"")
                    .append(points)
                    .append("\"/>");
        }
        sb.append(dots);
    }

    /** Lowest axis value: just under the smallest plotted score, rounded down to 0.1. */
    private static double axisFloor(List<HistoryEntry> timeline, List<String> metrics) {
        double min = 1;
        for (HistoryEntry e : timeline) {
            if (e.passRate() != null) {
                min = Math.min(min, e.passRate());
            }
            if (e.metricAverages() != null) {
                for (String m : metrics) {
                    Double v = e.metricAverages().get(m);
                    if (v != null) {
                        min = Math.min(min, v);
                    }
                }
            }
        }
        double lo = Math.floor((min - 0.03) * 10) / 10;
        return Math.max(0, Math.min(lo, 0.9));
    }

    static String shortSha(String sha) {
        return sha == null ? "" : sha.length() > 8 ? sha.substring(0, 8) : sha;
    }
}
