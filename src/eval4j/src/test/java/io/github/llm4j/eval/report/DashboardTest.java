package io.github.llm4j.eval.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

@org.junit.jupiter.api.parallel.ResourceLock("eval4j-recorder")
class DashboardTest {

    private static EvalRecord rec(
            String test, String metric, double score, double threshold, String reason) {
        return new EvalRecord(
                "com.acme.ChatEval",
                test,
                metric,
                score,
                threshold,
                score >= threshold,
                reason,
                "judge-x",
                "2026-01-01T00:00:00Z");
    }

    private static EvalRecord detailed(String test, String metric, double score, String reason) {
        return new EvalRecord(
                "com.acme.ChatEval",
                test,
                metric,
                score,
                0.5,
                score >= 0.5,
                reason,
                "judge-x",
                "2026-01-01T00:00:00Z",
                "What is the refund window?",
                "Refunds take 30 days.",
                "Refunds are accepted within 14 days.",
                List.of("Policy: 14 day window.", "Shipping takes 3 days."),
                1234L);
    }

    private static EvalReportWriter.RunInfo run(
            String id, List<EvalRecord> records, List<TestOutcome> tests) {
        return new EvalReportWriter.RunInfo(
                id,
                "2026-01-01T00:00:00Z",
                "2026-01-01T00:01:05Z",
                "0123456789abcdef",
                records,
                tests);
    }

    private static EvalReportWriter.RunInfo run(String id, List<EvalRecord> records) {
        return run(id, records, List.of());
    }

    // --- case drill-down -------------------------------------------------------------------

    @Test
    void drillDownShowsInputOutputExpectedAndRetrievedContext() {
        String html =
                EvalReportWriter.html(
                        run(
                                "r1",
                                List.of(detailed("t1", "Faithfulness", 0.2, "contradicts policy"))),
                        List.of(),
                        Map.of());
        assertThat(html)
                .contains("What is the refund window?")
                .contains("Refunds take 30 days.")
                .contains("Refunds are accepted within 14 days.")
                .contains("Retrieved context (2)")
                .contains("Policy: 14 day window.")
                .contains("contradicts policy")
                .contains("Why it failed")
                .contains("1.2s");
    }

    @Test
    void hostileCaseTextIsEscapedEverywhere() {
        String evil = "</pre><script>alert('x')</script>\"onmouseover=\"alert(1)";
        var r =
                new EvalRecord(
                        evil,
                        evil,
                        evil,
                        0.1,
                        0.5,
                        false,
                        evil,
                        evil,
                        evil,
                        evil,
                        evil,
                        evil,
                        List.of(evil),
                        1L);
        String html =
                EvalReportWriter.html(
                        run(
                                "<b>run</b>",
                                List.of(r),
                                List.of(new TestOutcome(evil, evil, "FAILED", 1, evil))),
                        List.of(),
                        Map.of());
        assertThat(html).doesNotContain("<script>alert").doesNotContain("</pre><script>");
        assertThat(html).doesNotContain("<b>run</b>");
        // the only <script> is the dashboard's own, and it carries no report data
        assertThat(html.split("<script>", -1)).hasSize(2);
        assertThat(html).doesNotContain("\"onmouseover=\"");
    }

    @Test
    void dashboardIsCompleteWithoutJavascript() {
        var records = List.of(detailed("t1", "M", 0.2, "bad"), detailed("t2", "M", 0.9, "good"));
        String html = EvalReportWriter.html(run("r1", records), List.of(), Map.of());
        String withoutScript = html.replaceAll("(?s)<script>.*?</script>", "");
        assertThat(withoutScript)
                .contains("Heatmap")
                .contains("bad")
                .contains("good")
                .contains("<table id=\"metrics-table\"");
    }

    // --- run-over-run comparison -----------------------------------------------------------

    @Test
    void sinceLastRunSectionListsNewFailuresFixesAndDrops() {
        var before =
                List.of(
                        rec("regressed", "M", 0.9, 0.5, "ok"),
                        rec("fixed", "M", 0.1, 0.5, "bad"),
                        rec("dropped", "M", 0.9, 0.5, "ok"),
                        rec("steady", "M", 0.8, 0.5, "ok"));
        HistoryEntry prev = EvalReportWriter.historyEntry(run("old", before));
        var now =
                List.of(
                        rec("regressed", "M", 0.2, 0.5, "now wrong"),
                        rec("fixed", "M", 0.9, 0.5, "ok"),
                        rec("dropped", "M", 0.6, 0.5, "meh"),
                        rec("steady", "M", 0.81, 0.5, "ok"),
                        rec("brand-new", "M", 0.9, 0.5, "ok"));
        ReportAnalysis a = new ReportAnalysis(run("new", now), List.of(prev), Map.of());
        assertThat(a.newFailures).hasSize(1);
        assertThat(a.fixed).hasSize(1);
        assertThat(a.worse).hasSize(1);
        assertThat(a.better).isEmpty();
        assertThat(a.newCases).isEqualTo(1);

        String html = HtmlDashboard.render(a);
        assertThat(html)
                .contains("Since the previous run")
                .contains("Newly failing (1)")
                .contains("0.90 → 0.20")
                .contains("Fixed (1)");
        assertThat(MarkdownSummary.render(a))
                .contains("1 newly failing · 1 fixed · 1 dropped · 0 improved · 1 new");
    }

    @Test
    void historyContainingTheCurrentRunDoesNotCompareAgainstItself() {
        var records = List.of(rec("t", "M", 0.9, 0.5, "ok"));
        var info = run("same", records);
        ReportAnalysis a =
                new ReportAnalysis(info, List.of(EvalReportWriter.historyEntry(info)), Map.of());
        assertThat(a.previous).isNull();
        assertThat(a.timeline).hasSize(1);
    }

    @Test
    void oldHistoryEntriesWithoutCaseScoresStillRender() {
        var old = new HistoryEntry("o", "2025-12-31T00:00:00Z", null, Map.of("M", 0.4));
        String html =
                EvalReportWriter.html(
                        run("n", List.of(rec("t", "M", 0.9, 0.5, "ok"))), List.of(old), Map.of());
        assertThat(html)
                .contains("<polyline")
                .contains("Trends")
                .doesNotContain("Since the previous run");
        assertThat(html).contains("+0.500");
    }

    @Test
    void trendChartHandlesRunsWithAndWithoutPassRate() {
        var h1 = new HistoryEntry("a", "t1", "abc", Map.of("M", 0.5), 0.5, 10, Map.of());
        var h2 = new HistoryEntry("b", "t2", null, Map.of("M", 0.6));
        String html =
                EvalReportWriter.html(
                        run("c", List.of(rec("t", "M", 0.9, 0.5, "ok"))),
                        List.of(h1, h2),
                        Map.of());
        assertThat(html).contains("class=\"trend\"").contains("pass rate");
    }

    // --- verdict, tests, heatmap ----------------------------------------------------------

    @Test
    void verdictReflectsFailuresRegressionsAndEmptiness() {
        String pass =
                EvalReportWriter.html(
                        run("r", List.of(rec("t", "M", 0.9, 0.5, "ok"))), List.of(), Map.of());
        assertThat(pass).contains("verdict pass").contains("PASSING");
        String fail =
                EvalReportWriter.html(
                        run("r", List.of(rec("t", "M", 0.1, 0.5, "no"))), List.of(), Map.of());
        assertThat(fail).contains("verdict fail").contains("FAILING");
        String reg =
                EvalReportWriter.html(
                        run("r", List.of(rec("t", "M", 0.9, 0.5, "ok"))),
                        List.of(),
                        Map.of("M", 0.99));
        assertThat(reg).contains("verdict reg").contains("REGRESSION");
        String none = EvalReportWriter.html(run("r", List.of()), List.of(), Map.of());
        assertThat(none).contains("NO EVALUATIONS").contains("No judged evaluations were recorded");
    }

    @Test
    void aFailedJUnitTestWithNoEvaluationsStillFailsTheRun() {
        var tests = List.of(new TestOutcome("a.B", "boom()", "FAILED", 5, "expected 1 but was 2"));
        String html = EvalReportWriter.html(run("r", List.of(), tests), List.of(), Map.of());
        assertThat(html)
                .contains("FAILING")
                .contains("expected 1 but was 2")
                .contains("id=\"tests\"");
    }

    @Test
    void heatmapCapsRowsAndLinksCellsToEvaluations() {
        var records = new ArrayList<EvalRecord>();
        for (int i = 0; i < 300; i++) {
            records.add(rec("t" + i, "M", i / 300.0, 0.5, "r"));
        }
        String html = EvalReportWriter.html(run("r", records), List.of(), Map.of());
        assertThat(html).contains("Showing the 120 lowest-scoring of 300 tests");
        assertThat(html).contains("<a href=\"#e0\"").contains("id=\"e0\"");
    }

    // --- other outputs ---------------------------------------------------------------------

    @Test
    void junitXmlIsWellFormedEvenForHostileText() throws Exception {
        String evil = "<&\"'>\u0000\u0001 bad";
        var records = List.of(rec(evil, evil, 0.1, 0.5, evil), rec("ok", "M", 0.9, 0.5, "fine"));
        String xml = JUnitXmlWriter.render(records);
        Document doc =
                DocumentBuilderFactory.newInstance()
                        .newDocumentBuilder()
                        .parse(new InputSource(new java.io.StringReader(xml)));
        assertThat(doc.getElementsByTagName("testcase").getLength()).isEqualTo(2);
        assertThat(doc.getElementsByTagName("failure").getLength()).isEqualTo(1);
        assertThat(
                        doc.getElementsByTagName("testsuite")
                                .item(0)
                                .getAttributes()
                                .getNamedItem("failures")
                                .getNodeValue())
                .isEqualTo("1");
    }

    @Test
    void csvQuotesCellsAndNeutralisesSpreadsheetFormulas() {
        String csv =
                CsvWriter.render(
                        List.of(
                                rec(
                                        "=HYPERLINK(\"x\")",
                                        "M",
                                        0.5,
                                        0.5,
                                        "line1\nline \"2\", with comma")));
        assertThat(csv).contains("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(csv).contains("\"line1\nline \"\"2\"\", with comma\"");
        assertThat(csv.lines().findFirst().get()).startsWith("suite,test,metric,score");
    }

    @Test
    void markdownSummaryEscapesPipesAndListsFailures() {
        var records =
                List.of(
                        rec("a|b", "M|N", 0.1, 0.5, "x | y\nz <b>"),
                        rec("ok", "M|N", 0.9, 0.5, "f"));
        String md =
                MarkdownSummary.render(new ReportAnalysis(run("r", records), List.of(), Map.of()));
        assertThat(md)
                .contains("1 / 2 evaluations passed (50%)")
                .contains("a\\|b")
                .contains("M\\|N")
                .contains("x \\| y z &lt;b&gt;")
                .contains("1 failing evaluation(s)");
    }

    @Test
    void writeProducesAllFiveFiles(@TempDir Path dir) {
        EvalReportWriter.write(
                dir, run("r", List.of(rec("t", "M", 0.9, 0.5, "ok"))), List.of(), Map.of());
        for (String f :
                List.of(
                        EvalReportWriter.HTML_FILE,
                        EvalReportWriter.JSON_FILE,
                        EvalReportWriter.JUNIT_FILE,
                        EvalReportWriter.MARKDOWN_FILE,
                        EvalReportWriter.CSV_FILE)) {
            assertThat(dir.resolve(f)).exists().isNotEmptyFile();
        }
    }

    // --- compatibility ---------------------------------------------------------------------

    @Test
    void reportsWrittenBeforeTheDashboardStillLoad() throws Exception {
        String legacy =
                "{\"runId\":\"x\",\"startedAt\":\"2026-01-01T00:00:00Z\",\"endedAt\":\"2026-01-01T00:00:01Z\","
                        + "\"gitSha\":null,\"records\":[{\"suite\":\"S\",\"testName\":\"t\",\"metric\":\"M\","
                        + "\"score\":0.5,\"threshold\":0.5,\"passed\":true,\"reason\":\"r\","
                        + "\"judgeIdentifier\":null,\"timestamp\":\"t\"}]}";
        var run = new ObjectMapper().readValue(legacy, EvalReportWriter.RunInfo.class);
        assertThat(run.records()).hasSize(1);
        assertThat(run.records().get(0).input()).isNull();
        assertThat(run.tests()).isNull();
        assertThat(EvalReportWriter.html(run, List.of(), Map.of())).contains("1 / 1");
    }

    // --- recorder & extension --------------------------------------------------------------

    @Test
    void recorderBoundsHugeCaseText() {
        EvalRecorder.reset();
        EvalRecorder.activate();
        try {
            String huge = "x".repeat(100_000);
            EvalRecorder.record(
                    "M", 1, 0.5, "r", null, new EvalDetails(huge, huge, null, List.of(huge), 7L));
            EvalRecord r = EvalRecorder.records().get(0);
            assertThat(r.input()).hasSizeLessThan(21_000).endsWith("chars]");
            assertThat(r.retrievalContext().get(0)).hasSizeLessThan(21_000);
            assertThat(r.durationMs()).isEqualTo(7L);
        } finally {
            EvalRecorder.reset();
        }
    }

    @ExtendWith(EvalReportExtension.class)
    static class Fixture {
        @Test
        void passes() {
            EvalRecorder.record(
                    "M", 0.9, 0.5, "good", "j", new EvalDetails("q", "a", "e", List.of("c"), 5L));
        }

        @Test
        void failsPlainly() {
            throw new AssertionError("plain failure");
        }
    }

    @BeforeEach
    @AfterEach
    void clean() {
        EvalRecorder.reset();
        System.clearProperty(EvalReportExtension.REPORT_DIR_PROPERTY);
    }

    @Test
    void extensionWritesTestOutcomesCaseDetailsAndPerCaseHistory(@TempDir Path dir)
            throws Exception {
        System.setProperty(EvalReportExtension.REPORT_DIR_PROPERTY, dir.toString());
        EngineTestKit.engine("junit-jupiter").selectors(selectClass(Fixture.class)).execute();

        var run =
                new ObjectMapper()
                        .readValue(
                                dir.resolve("eval4j-report.json").toFile(),
                                EvalReportWriter.RunInfo.class);
        assertThat(run.tests())
                .extracting(TestOutcome::status)
                .containsExactlyInAnyOrder("PASSED", "FAILED");
        assertThat(run.tests())
                .filteredOn(t -> !t.passed())
                .extracting(TestOutcome::message)
                .containsExactly("plain failure");
        assertThat(run.records().get(0).input()).isEqualTo("q");

        var history = new FileSystemScoreHistory(dir.resolve("eval4j-history.jsonl")).load();
        assertThat(history).hasSize(1);
        assertThat(history.get(0).passRate()).isEqualTo(1.0);
        assertThat(history.get(0).caseScores()).hasSize(1);
        assertThat(Files.readString(dir.resolve("eval4j-report.html")))
                .contains("plain failure")
                .contains("FAILING");
    }

    // --- CLI -------------------------------------------------------------------------------

    @Test
    void cliMergesReportsFromSeveralModules(@TempDir Path dir) throws Exception {
        Path a = dir.resolve("a");
        Path b = dir.resolve("b");
        EvalReportWriter.write(
                a, run("ra", List.of(rec("t1", "M", 0.9, 0.5, "ok"))), List.of(), Map.of());
        EvalReportWriter.write(
                b, run("rb", List.of(rec("t2", "M", 0.1, 0.5, "bad"))), List.of(), Map.of());
        Path out = dir.resolve("merged");
        EvalReportCli.main(
                new String[] {
                    "--out",
                    out.toString(),
                    a.resolve("eval4j-report.json").toString(),
                    b.resolve("eval4j-report.json").toString()
                });
        String html = Files.readString(out.resolve("eval4j-report.html"));
        assertThat(html).contains("1 / 2").contains("bad").contains("FAILING");
    }

    @Test
    void cliRejectsMissingOptionValue() {
        assertThrows(
                IllegalArgumentException.class, () -> EvalReportCli.main(new String[] {"--out"}));
    }
}
