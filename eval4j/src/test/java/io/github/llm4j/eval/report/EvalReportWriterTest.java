package io.github.llm4j.eval.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvalReportWriterTest {

    private static EvalRecord rec(String test, String metric, double score, String reason) {
        return new EvalRecord(
                "Suite",
                test,
                metric,
                score,
                0.5,
                score >= 0.5,
                reason,
                "judge-x",
                "2026-01-01T00:00:00Z");
    }

    private static EvalReportWriter.RunInfo run(List<EvalRecord> records) {
        return new EvalReportWriter.RunInfo(
                "run-1", "2026-01-01T00:00:00Z", "2026-01-01T00:01:00Z", "abc123", records);
    }

    @Test
    void writesJsonThatRoundTripsIntoRecords(@TempDir Path dir) throws Exception {
        var records =
                List.of(
                        rec("t1", "Faithfulness", 0.75, "ok"),
                        rec("t2", "Faithfulness", 0.25, "bad"));
        EvalReportWriter.write(dir, run(records), List.of(), Map.of());

        JsonNode root =
                new ObjectMapper().readTree(Files.readAllBytes(dir.resolve("eval4j-report.json")));
        assertThat(root.get("runId").asText()).isEqualTo("run-1");
        assertThat(root.get("gitSha").asText()).isEqualTo("abc123");
        List<EvalRecord> back =
                new ObjectMapper().readerForListOf(EvalRecord.class).readValue(root.get("records"));
        assertThat(back).isEqualTo(records);
    }

    @Test
    void htmlIsSelfContained_noExternalReferences(@TempDir Path dir) throws Exception {
        EvalReportWriter.write(dir, run(List.of(rec("t", "M", 0.9, "fine"))), List.of(), Map.of());
        String html = Files.readString(dir.resolve("eval4j-report.html"));
        assertThat(html).doesNotContain("http://").doesNotContain("https://");
        assertThat(html).doesNotContainPattern("(src|href)=");
    }

    @Test
    void htmlEscapesHostileStrings(@TempDir Path dir) throws Exception {
        String payload = "<script>alert(1)</script> & \"quoted\" 'single'";
        EvalReportWriter.write(
                dir, run(List.of(rec(payload, payload, 0.1, payload))), List.of(), Map.of());
        String html = Files.readString(dir.resolve("eval4j-report.html"));
        assertThat(html).doesNotContain("<script>alert(1)</script>");
        assertThat(html)
                .contains(
                        "&lt;script&gt;alert(1)&lt;/script&gt; &amp; &quot;quoted&quot; &#39;single&#39;");
    }

    @Test
    void htmlShowsSummaryFailingReasonAndBaselineDelta() {
        var records = List.of(rec("t1", "M", 0.9, "great"), rec("t2", "M", 0.1, "wrong answer"));
        String html = EvalReportWriter.html(run(records), List.of(), Map.of("M", 0.9));
        assertThat(html)
                .contains("1 / 2")
                .contains("50%")
                .contains("wrong answer")
                .contains("-0.400");
        assertThat(html).contains("class=\"reg\"");
    }

    @Test
    void sparklineOnlyWithAtLeastTwoRuns() {
        var records = List.of(rec("t", "M", 0.9, "r"));
        var one = List.of(new HistoryEntry("r0", "t", null, Map.of("M", 0.5)));
        assertThat(EvalReportWriter.html(run(records), List.of(), Map.of()))
                .doesNotContain("<polyline");
        assertThat(EvalReportWriter.html(run(records), one, Map.of())).contains("<polyline");
    }

    @Test
    void largeReportGeneratesQuickly() {
        var records = new java.util.ArrayList<EvalRecord>();
        for (int i = 0; i < 10_000; i++) {
            records.add(rec("t" + i, "M" + (i % 5), (i % 10) / 10.0, "reason " + i));
        }
        long start = System.nanoTime();
        String html = EvalReportWriter.html(run(records), List.of(), Map.of());
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(5_000);
        assertThat(html).contains("10000");
    }

    @Test
    void reportWriteFailureLeavesNoPartialFile(@TempDir Path dir) throws Exception {
        Path blocker = dir.resolve("blocked");
        Files.writeString(blocker, "i am a file, not a directory");
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> EvalReportWriter.write(blocker, run(List.of()), List.of(), Map.of()));
        assertThat(Files.readString(blocker)).isEqualTo("i am a file, not a directory");
    }
}
