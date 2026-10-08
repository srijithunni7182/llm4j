package io.github.llm4j.evalreport.render;

import io.github.llm4j.evalreport.model.ReportModel;
import io.github.llm4j.evalreport.model.ReportModel.CaseView;

/** One row per evaluation, for spreadsheets. Fields starting with = + - @ are neutralised. */
public final class Csv {

    private Csv() {}

    public static String render(ReportModel m) {
        StringBuilder sb =
                new StringBuilder(
                        "case,metric,dimension,kind,status,source,score,threshold,passed,reason\n");
        for (CaseView c : m.cases()) {
            c.evaluations()
                    .forEach(
                            e ->
                                    sb.append(Escape.csv(c.name()))
                                            .append(',')
                                            .append(Escape.csv(e.metric()))
                                            .append(',')
                                            .append(Escape.csv(c.dims().get(e.key())))
                                            .append(',')
                                            .append(Escape.csv(e.kind()))
                                            .append(',')
                                            .append(Escape.csv(e.status()))
                                            .append(',')
                                            .append(Escape.csv(e.sourceOrFresh()))
                                            .append(',')
                                            .append(e.score() == null ? "" : e.score())
                                            .append(',')
                                            .append(e.threshold() == null ? "" : e.threshold())
                                            .append(',')
                                            .append(e.passed() == null ? "" : e.passed())
                                            .append(',')
                                            .append(Escape.csv(e.reason()))
                                            .append('\n'));
        }
        return sb.toString();
    }
}
