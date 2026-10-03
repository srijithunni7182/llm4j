package io.github.llm4j.evalreport.spi;

import io.github.llm4j.eval.export.RunExportListener;
import io.github.llm4j.evalreport.EvalReport;
import io.github.llm4j.evalreport.config.ReportConfig;
import io.github.llm4j.evalreport.format.RunBundleReader;
import io.github.llm4j.evalreport.format.RunStore;
import io.github.llm4j.evalreport.model.ReportModel;
import io.github.llm4j.evalreport.render.Csv;
import io.github.llm4j.evalreport.render.HtmlRenderer;
import io.github.llm4j.evalreport.render.JUnitXml;
import io.github.llm4j.evalreport.render.MarkdownSummary;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Renders the dashboard when an eval4j test JVM finishes a run. Loaded by {@link
 * java.util.ServiceLoader} only inside a JVM that has eval4j on the classpath. Never throws: a
 * report failure must not fail the build.
 */
public final class ReportRunExportListener implements RunExportListener {

    @Override
    public void runFinished(Path root, Path runDir) {
        try {
            ReportConfig config = ReportConfig.load(Path.of("eval4j-report.yaml"));
            String runId =
                    RunBundleReader.MAPPER
                            .readTree(runDir.resolve("run.json").toFile())
                            .path("runId")
                            .asText();
            ReportModel m = EvalReport.build(new RunStore(root), runId, null, true, config);
            Path out = root.resolve("report");
            Files.createDirectories(out);
            Files.writeString(
                    out.resolve("index.html"), HtmlRenderer.render(m), StandardCharsets.UTF_8);
            Files.writeString(
                    out.resolve("summary.md"), MarkdownSummary.summary(m), StandardCharsets.UTF_8);
            Files.writeString(out.resolve("junit.xml"), JUnitXml.render(m), StandardCharsets.UTF_8);
            Files.writeString(
                    out.resolve("evaluations.csv"), Csv.render(m), StandardCharsets.UTF_8);
            System.out.println("eval4j-report: dashboard written to " + out.resolve("index.html"));
        } catch (IOException | RuntimeException e) {
            System.err.println("eval4j-report: could not render the dashboard: " + e);
        }
    }
}
