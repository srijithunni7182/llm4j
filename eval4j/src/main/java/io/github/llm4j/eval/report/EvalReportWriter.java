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
 * Writes a run's results into a report directory:
 *
 * <ul>
 *   <li>{@code eval4j-report.html} — the dashboard: one self-contained file (inline CSS/SVG, no
 *       external requests) that is complete without JavaScript;
 *   <li>{@code eval4j-report.json} — the full run, the source of truth the other files (and {@link
 *       EvalReportCli}) are rendered from;
 *   <li>{@code eval4j-junit.xml} — one test case per evaluation, for CI test reporters;
 *   <li>{@code eval4j-summary.md} — a short digest for pull-request comments and job summaries;
 *   <li>{@code eval4j-report.csv} — every evaluation, for spreadsheets.
 * </ul>
 *
 * Every dynamic value is escaped for the format it lands in.
 */
public final class EvalReportWriter {

    public static final String JSON_FILE = "eval4j-report.json";
    public static final String HTML_FILE = "eval4j-report.html";
    public static final String JUNIT_FILE = "eval4j-junit.xml";
    public static final String MARKDOWN_FILE = "eval4j-summary.md";
    public static final String CSV_FILE = "eval4j-report.csv";

    /** Run-level metadata plus all records and test outcomes; the JSON report's shape. */
    public record RunInfo(
            String runId,
            String startedAt,
            String endedAt,
            String gitSha,
            List<EvalRecord> records,
            List<TestOutcome> tests) {

        /** A run without JUnit test outcomes (also the shape of reports from before the dashboard). */
        public RunInfo(
                String runId,
                String startedAt,
                String endedAt,
                String gitSha,
                List<EvalRecord> records) {
            this(runId, startedAt, endedAt, gitSha, records, List.of());
        }
    }

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private EvalReportWriter() {}

    /**
     * @param history prior runs (oldest first) for trends and the "since previous run" section; may
     *     be empty
     * @param baselineAverages per-metric baseline averages for a delta column; may be empty
     */
    public static void write(
            Path dir,
            RunInfo run,
            List<HistoryEntry> history,
            Map<String, Double> baselineAverages) {
        try {
            ReportAnalysis analysis = new ReportAnalysis(run, history, baselineAverages);
            AtomicFiles.write(dir.resolve(JSON_FILE), MAPPER.writeValueAsBytes(run));
            AtomicFiles.write(dir.resolve(HTML_FILE), utf8(HtmlDashboard.render(analysis)));
            AtomicFiles.write(dir.resolve(JUNIT_FILE), utf8(JUnitXmlWriter.render(analysis.records)));
            AtomicFiles.write(dir.resolve(MARKDOWN_FILE), utf8(MarkdownSummary.render(analysis)));
            AtomicFiles.write(dir.resolve(CSV_FILE), utf8(CsvWriter.render(analysis.records)));
        } catch (IOException e) {
            throw new IllegalStateException("Could not write eval4j report to " + dir, e);
        }
    }

    /** The history entry to append for this run: averages, pass rate and per-case scores. */
    public static HistoryEntry historyEntry(RunInfo run) {
        List<EvalRecord> records = run.records();
        long passed = records.stream().filter(EvalRecord::passed).count();
        return new HistoryEntry(
                run.runId(),
                run.endedAt(),
                run.gitSha(),
                metricAverages(records),
                records.isEmpty() ? null : (double) passed / records.size(),
                records.size(),
                ReportAnalysis.caseScores(records));
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
        return HtmlDashboard.render(new ReportAnalysis(run, history, baselineAverages));
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
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
