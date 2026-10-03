package io.github.llm4j.eval.report;

import java.util.List;
import java.util.Locale;

/** Writes every evaluation as RFC 4180 CSV for spreadsheets and ad-hoc analysis. */
final class CsvWriter {

    private static final String HEADER =
            "suite,test,metric,score,threshold,passed,reason,judge,timestamp,duration_ms,input,"
                    + "actual_output,expected_output\n";

    private CsvWriter() {}

    static String render(List<EvalRecord> records) {
        StringBuilder sb = new StringBuilder(HEADER);
        for (EvalRecord r : records) {
            sb.append(cell(r.suite())).append(',')
                    .append(cell(r.testName())).append(',')
                    .append(cell(r.metric())).append(',')
                    .append(String.format(Locale.ROOT, "%.4f,%.4f", r.score(), r.threshold())).append(',')
                    .append(r.passed()).append(',')
                    .append(cell(r.reason())).append(',')
                    .append(cell(r.judgeIdentifier())).append(',')
                    .append(cell(r.timestamp())).append(',')
                    .append(r.durationMs() == null ? "" : r.durationMs()).append(',')
                    .append(cell(r.input())).append(',')
                    .append(cell(r.actualOutput())).append(',')
                    .append(cell(r.expectedOutput())).append('\n');
        }
        return sb.toString();
    }

    /**
     * Quotes a cell. A leading {@code = + - @} (or tab/CR) is prefixed with an apostrophe so a
     * spreadsheet never evaluates model output as a formula.
     */
    static String cell(String s) {
        if (s == null) {
            return "";
        }
        String v = s;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        return '"' + v.replace("\"", "\"\"") + '"';
    }
}
