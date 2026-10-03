package io.github.llm4j.evalreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.evalreport.analysis.Baselines;
import io.github.llm4j.evalreport.config.ReportConfig;
import io.github.llm4j.evalreport.format.RunBundle;
import io.github.llm4j.evalreport.format.RunBundleReader;
import io.github.llm4j.evalreport.format.RunBundleReader.BundleFormatException;
import io.github.llm4j.evalreport.format.RunStore;
import io.github.llm4j.evalreport.format.model.RunMeta;
import io.github.llm4j.evalreport.render.Csv;
import io.github.llm4j.evalreport.render.Escape;
import io.github.llm4j.evalreport.render.JUnitXml;
import io.github.llm4j.evalreport.render.MarkdownSummary;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReaderAndBaselineTest {

    private static String header(String id, String branch, String start, String status) {
        return "{\"schemaVersion\":1,\"format\":\"eval4j-run\",\"runId\":\""
                + id
                + "\",\"status\":\""
                + status
                + "\",\"startedAt\":\""
                + start
                + "\",\"project\":{\"name\":\"p\"},\"source\":{\"branch\":\""
                + branch
                + "\"},\"profile\":{\"name\":\"BUILD\"},\"metrics\":[{\"id\":\"m\",\"name\":\"M\",\"kind\":\"JUDGE\",\"dimension\":\"correctness\"}]}";
    }

    private static void bundle(
            Path root, String id, String branch, String start, String status, String evals)
            throws IOException {
        Path d = root.resolve("runs").resolve(id);
        Files.createDirectories(d);
        Files.writeString(d.resolve("run.json"), header(id, branch, start, status));
        Files.writeString(d.resolve("evaluations.jsonl"), evals);
    }

    private static final String E1 =
            "{\"seq\":0,\"key\":\"k_1\",\"caseId\":\"c_1\",\"metric\":\"m\",\"kind\":\"JUDGE\",\"status\":\"EVALUATED\",\"score\":0.9,\"passed\":true}\n";

    @Test
    void lenientReaderSkipsBadLinesAndToleratesATruncatedLastLine(@TempDir Path tmp)
            throws Exception {
        bundle(
                tmp,
                "RUN-AAAAAA",
                "main",
                "2026-01-01T00:00:00Z",
                "COMPLETE",
                E1
                        + "not json\n{\"seq\":1,\"key\":\"k_2\",\"metric\":\"m\",\"status\":\"EVALUATED\",\"score\":7,\"passed\":true}\n"
                        + "{\"seq\":2,\"key\":\"k_3\",\"metric\":\"m\",\"status\":\"EVALUATED\"}\n{\"seq\":3,\"ke");
        RunBundle b = new RunStore(tmp).load("RUN-AAAAAA", false);
        assertThat(b.evaluations()).hasSize(3);
        assertThat(b.evaluations().get(1).score()).isEqualTo(1.0); // clamped
        assertThat(b.evaluations().get(2).status()).isEqualTo("ERROR"); // no verdict
        assertThat(b.warnings())
                .anyMatch(w -> w.contains("skipped"))
                .anyMatch(w -> w.contains("clamped"))
                .anyMatch(w -> w.contains("treated as ERROR"));
        assertThatThrownBy(() -> new RunStore(tmp).load("RUN-AAAAAA", true))
                .isInstanceOf(BundleFormatException.class)
                .hasMessageContaining("evaluations.jsonl:2");
    }

    @Test
    void readsGzipAndRejectsNewerSchemaAndMissingHeader(@TempDir Path tmp) throws Exception {
        bundle(tmp, "RUN-BBBBBB", "main", "2026-01-01T00:00:00Z", "COMPLETE", "");
        Path d = tmp.resolve("runs/RUN-BBBBBB");
        Files.delete(d.resolve("evaluations.jsonl"));
        try (OutputStream o =
                new GZIPOutputStream(Files.newOutputStream(d.resolve("evaluations.jsonl.gz")))) {
            o.write(E1.getBytes(StandardCharsets.UTF_8));
        }
        assertThat(new RunStore(tmp).load("RUN-BBBBBB", true).evaluations()).hasSize(1);
        Files.writeString(
                d.resolve("run.json"),
                header("RUN-BBBBBB", "main", "2026-01-01T00:00:00Z", "COMPLETE")
                        .replace("\"schemaVersion\":1", "\"schemaVersion\":2"));
        assertThatThrownBy(() -> RunBundleReader.read(d, false))
                .hasMessageContaining("schemaVersion 2");
        assertThatThrownBy(() -> RunBundleReader.read(tmp.resolve("nope"), false))
                .hasMessageContaining("not a run bundle");
    }

    @Test
    void baselineIsPreviousRunOnSameBranchThenDefaultBranch(@TempDir Path tmp) throws Exception {
        bundle(tmp, "RUN-000001", "main", "2026-01-01T00:00:00Z", "COMPLETE", E1);
        bundle(tmp, "RUN-000002", "feature/x", "2026-01-02T00:00:00Z", "COMPLETE", E1);
        bundle(tmp, "RUN-000003", "feature/x", "2026-01-03T00:00:00Z", "FAILED", E1);
        bundle(tmp, "RUN-000004", "feature/x", "2026-01-04T00:00:00Z", "COMPLETE", E1);
        bundle(tmp, "RUN-000005", "feature/y", "2026-01-05T00:00:00Z", "COMPLETE", E1);
        List<RunMeta> runs = new RunStore(tmp).list();
        ReportConfig cfg = ReportConfig.defaults();
        RunMeta c4 = runs.get(3);
        Baselines.Pick p = Baselines.select(c4, runs, cfg, null);
        assertThat(p.run().runId()).isEqualTo("RUN-000002"); // skips the FAILED run
        assertThat(p.label()).contains("Previous run on branch feature/x");
        Baselines.Pick fb = Baselines.select(runs.get(4), runs, cfg, null);
        assertThat(fb.run().runId()).isEqualTo("RUN-000001");
        assertThat(fb.label()).contains("Baseline from main").contains("no earlier run");
        assertThat(Baselines.select(runs.get(0), runs, cfg, null)).isNull();
        assertThat(Baselines.select(c4, runs, cfg, "RUN-000005").run().runId())
                .isEqualTo("RUN-000005");
        assertThatThrownBy(() -> Baselines.select(c4, runs, cfg, "zzz"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void textOutputsEscapeAndCarryNoVerdict(@TempDir Path tmp) throws Exception {
        bundle(
                tmp,
                "RUN-CCCCCC",
                "main",
                "2026-01-01T00:00:00Z",
                "COMPLETE",
                "{\"seq\":0,\"key\":\"k_1\",\"caseId\":\"c_1\",\"testId\":\"=cmd|'/c calc'!A1\",\"metric\":\"m\",\"kind\":\"JUDGE\",\"status\":\"EVALUATED\",\"score\":0.1,\"passed\":false,\"reason\":\"a,\\\"b\\\"\\n<x>\"}\n");
        var m = EvalReport.build(new RunStore(tmp), null, null, true, ReportConfig.defaults());
        String csv = Csv.render(m);
        assertThat(csv).contains("\"a,\"\"b\"\"\n<x>\"");
        assertThat(Escape.csv("=SUM(A1)")).isEqualTo("'=SUM(A1)");
        String xml = JUnitXml.render(m);
        assertThat(xml).contains("&lt;x&gt;").doesNotContain("<x>").contains("<failure");
        assertThat(MarkdownSummary.summary(m)).contains("| Correctness |");
        assertThat(MarkdownSummary.compare(m, 5)).contains("No baseline");
        assertThat(Escape.html("<a href=\"x\">&'</a>"))
                .isEqualTo("&lt;a href=&quot;x&quot;&gt;&amp;&#39;&lt;/a&gt;");
        assertThat(Escape.html(null)).isEmpty();
        assertThat(Escape.csv(null)).isEmpty();
    }

    @Test
    void aRepeatedKeyKeepsTheLaterLine(@TempDir Path tmp) throws Exception {
        String ne =
                "{\"seq\":0,\"key\":\"k_1\",\"caseId\":\"c_1\",\"metric\":\"m\",\"kind\":\"JUDGE\",\"status\":\"NOT_EVALUATED\"}\n";
        String carried =
                "{\"seq\":1,\"key\":\"k_1\",\"caseId\":\"c_1\",\"metric\":\"m\",\"kind\":\"JUDGE\",\"status\":\"EVALUATED\",\"source\":\"CARRIED\",\"evaluatedInRun\":\"r0\",\"score\":0.9,\"passed\":true}\n";
        bundle(tmp, "RUN-DDDDDD", "main", "2026-01-01T00:00:00Z", "COMPLETE", ne + carried);
        RunBundle b = new RunStore(tmp).load("RUN-DDDDDD", false);
        assertThat(b.evaluations()).hasSize(1);
        assertThat(b.evaluations().get(0).source()).isEqualTo("CARRIED");
        assertThat(b.warnings()).isEmpty();
        bundle(tmp, "RUN-EEEEEE", "main", "2026-01-02T00:00:00Z", "COMPLETE", E1 + E1);
        assertThat(new RunStore(tmp).load("RUN-EEEEEE", false).warnings())
                .anyMatch(w -> w.contains("appears twice"));
    }
}
