package io.github.llm4j.evalreport.render;

import io.github.llm4j.evalreport.model.ReportModel;
import io.github.llm4j.evalreport.model.ReportModel.CaseView;
import java.util.Locale;

/** JUnit XML so any CI can show one test per case. Failures carry the failing reasons. */
public final class JUnitXml {

    private JUnitXml() {}

    public static String render(ReportModel m) {
        int failures = 0;
        int skipped = 0;
        for (CaseView c : m.cases()) {
            if ("FAILED".equals(c.outcome())) {
                failures++;
            } else if ("NOT_EVALUATED".equals(c.outcome())) {
                skipped++;
            }
        }
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<testsuite name=\"eval4j\" tests=\"")
                .append(m.cases().size())
                .append("\" failures=\"")
                .append(failures)
                .append("\" skipped=\"")
                .append(skipped)
                .append("\">\n");
        for (CaseView c : m.cases()) {
            sb.append("  <testcase classname=\"eval4j\" name=\"")
                    .append(Escape.html(c.name()))
                    .append("\">");
            if ("FAILED".equals(c.outcome())) {
                StringBuilder msg = new StringBuilder();
                c.evaluations()
                        .forEach(
                                e -> {
                                    if (e.counted() && !e.passed()) {
                                        msg.append(e.metric());
                                        if (e.score() != null) {
                                            msg.append(
                                                    String.format(Locale.ROOT, " %.2f", e.score()));
                                        }
                                        if (e.reason() != null) {
                                            msg.append(": ").append(e.reason());
                                        }
                                        msg.append('\n');
                                    }
                                });
                sb.append("<failure message=\"failed\">")
                        .append(Escape.html(msg.toString()))
                        .append("</failure>");
            } else if ("NOT_EVALUATED".equals(c.outcome())) {
                sb.append("<skipped/>");
            }
            sb.append("</testcase>\n");
        }
        return sb.append("</testsuite>\n").toString();
    }
}
