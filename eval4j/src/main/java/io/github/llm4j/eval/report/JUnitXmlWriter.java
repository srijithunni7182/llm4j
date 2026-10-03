package io.github.llm4j.eval.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes evaluations in the de-facto JUnit XML format, one {@code testcase} per judged evaluation
 * (named {@code test [metric]}), so Jenkins, GitLab, GitHub test reporters and IDEs can show eval
 * results beside ordinary test results. A below-threshold score is a {@code failure}.
 */
final class JUnitXmlWriter {

    private JUnitXmlWriter() {}

    static String render(List<EvalRecord> records) {
        Map<String, List<EvalRecord>> bySuite = new LinkedHashMap<>();
        for (EvalRecord r : records) {
            bySuite.computeIfAbsent(
                            r.suite() == null ? "eval4j" : r.suite(), k -> new ArrayList<>())
                    .add(r);
        }
        StringBuilder sb =
                new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuites>\n");
        bySuite.forEach(
                (suite, list) -> {
                    long failures = list.stream().filter(r -> !r.passed()).count();
                    sb.append("  <testsuite name=\"")
                            .append(xml(suite))
                            .append("\" tests=\"")
                            .append(list.size())
                            .append("\" failures=\"")
                            .append(failures)
                            .append("\" errors=\"0\" skipped=\"0\" time=\"")
                            .append(
                                    seconds(
                                            list.stream()
                                                    .mapToLong(
                                                            r ->
                                                                    r.durationMs() == null
                                                                            ? 0
                                                                            : r.durationMs())
                                                    .sum()))
                            .append("\">\n");
                    for (EvalRecord r : list) {
                        sb.append("    <testcase classname=\"")
                                .append(xml(suite))
                                .append("\" name=\"")
                                .append(
                                        xml(
                                                (r.testName() == null ? "" : r.testName() + " ")
                                                        + "["
                                                        + r.metric()
                                                        + "]"))
                                .append("\" time=\"")
                                .append(seconds(r.durationMs() == null ? 0 : r.durationMs()))
                                .append('"');
                        if (r.passed()) {
                            sb.append("/>\n");
                            continue;
                        }
                        sb.append(">\n      <failure message=\"")
                                .append(
                                        xml(
                                                String.format(
                                                        Locale.ROOT,
                                                        "%s scored %.3f, below threshold %.3f",
                                                        r.metric(),
                                                        r.score(),
                                                        r.threshold())))
                                .append("\" type=\"eval4j.ThresholdNotMet\">")
                                .append(xml(r.reason() == null ? "" : r.reason()))
                                .append("</failure>\n    </testcase>\n");
                    }
                    sb.append("  </testsuite>\n");
                });
        return sb.append("</testsuites>\n").toString();
    }

    private static String seconds(long ms) {
        return String.format(Locale.ROOT, "%.3f", ms / 1000.0);
    }

    /** Escapes text and strips characters that are illegal in XML 1.0. */
    static String xml(String s) {
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> {
                    boolean legal =
                            c == 0x9
                                    || c == 0xA
                                    || c == 0xD
                                    || (c >= 0x20 && c <= 0xD7FF)
                                    || (c >= 0xE000 && c <= 0xFFFD)
                                    || Character.isSurrogate(c);
                    if (legal) {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
