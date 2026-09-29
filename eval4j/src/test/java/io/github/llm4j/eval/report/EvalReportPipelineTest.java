package io.github.llm4j.eval.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import io.github.llm4j.eval.judge.LlmJudgeCondition;
import io.github.llm4j.eval.support.JudgeResponses;
import io.github.llm4j.eval.support.StubJudge;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;

/**
 * End-to-end tests for recorder → extension → report/baseline/history, running fixture test classes
 * through the real JUnit engine.
 */
@org.junit.jupiter.api.parallel.ResourceLock("eval4j-recorder")
class EvalReportPipelineTest {

    static final Path BASELINE = Path.of("target/eval4j-test/pipeline-baseline.json");
    static volatile double fixtureScore = 0.8;

    @ExtendWith(EvalReportExtension.class)
    @EvalBaseline(file = "target/eval4j-test/pipeline-baseline.json", maxRegression = 0.05)
    static class GatedFixture {
        @Test
        void a() {
            EvalRecorder.record("Faithfulness", fixtureScore, 0.5, "because", "judge-x");
        }

        @Test
        void b() {
            EvalRecorder.record("Faithfulness", fixtureScore, 0.5, "because", "judge-x");
        }
    }

    @ExtendWith(EvalReportExtension.class)
    static class PlainFixtureOne {
        @Test
        void one() {
            EvalRecorder.record("M", 0.9, 0.5, "r", null);
        }
    }

    @ExtendWith(EvalReportExtension.class)
    static class PlainFixtureTwo {
        @Test
        void two() {
            EvalRecorder.record("M", 0.1, 0.5, "r", null);
        }
    }

    @ExtendWith(EvalReportExtension.class)
    static class JudgeFixture {
        @Test
        void judged() {
            var cond =
                    LlmJudgeCondition.llmJudged("Correctness")
                            .criteria("c")
                            .judge(StubJudge.always(JudgeResponses.rating(5, "great")))
                            .judgeIdentifier("stub-judge")
                            .build();
            cond.matches("answer");
        }
    }

    @BeforeEach
    @AfterEach
    void clean() throws Exception {
        EvalRecorder.reset();
        Files.deleteIfExists(BASELINE);
        System.clearProperty(EvalReportExtension.REPORT_DIR_PROPERTY);
        System.clearProperty(EvalReportExtension.BASELINE_UPDATE_PROPERTY);
        System.clearProperty(EvalReportExtension.HISTORY_FILE_PROPERTY);
    }

    private static Events run(Class<?>... classes) {
        var selectors = java.util.Arrays.stream(classes).map(c -> selectClass(c)).toList();
        return EngineTestKit.engine("junit-jupiter")
                .selectors(selectors.toArray(new org.junit.platform.engine.DiscoverySelector[0]))
                .execute()
                .containerEvents();
    }

    private static List<String> containerFailures(Events events) {
        return events.failed().stream()
                .map(
                        e ->
                                e.getRequiredPayload(TestExecutionResult.class)
                                        .getThrowable()
                                        .get()
                                        .getMessage())
                .toList();
    }

    private static void updateBaseline(double score) {
        fixtureScore = score;
        System.setProperty(EvalReportExtension.BASELINE_UPDATE_PROPERTY, "true");
        run(GatedFixture.class);
        System.clearProperty(EvalReportExtension.BASELINE_UPDATE_PROPERTY);
        EvalRecorder.reset();
    }

    @Test
    void recordingIsOffWithoutTheExtension() {
        EvalRecorder.record("M", 1.0, 0.5, "r", null);
        assertThat(EvalRecorder.records()).isEmpty();
    }

    @Test
    void judgedConditionsAreRecordedWithTestIdentity() {
        System.setProperty(
                EvalReportExtension.REPORT_DIR_PROPERTY, "target/eval4j-test/report-judged");
        run(JudgeFixture.class);
        // run state closed => recorder reset; the JSON report holds the evidence
        String json = read("target/eval4j-test/report-judged/eval4j-report.json");
        assertThat(json)
                .contains("\"metric\" : \"Correctness\"")
                .contains("JudgeFixture")
                .contains("\"judgeIdentifier\" : \"stub-judge\"")
                .contains("\"passed\" : true");
    }

    @Test
    void gatePassesOnSameScoreAndAfterSmallDropAndImprovement() {
        updateBaseline(0.80);
        fixtureScore = 0.80;
        assertThat(containerFailures(run(GatedFixture.class))).isEmpty();
        fixtureScore = 0.77; // 0.03 drop
        assertThat(containerFailures(run(GatedFixture.class))).isEmpty();
        fixtureScore = 0.95;
        assertThat(containerFailures(run(GatedFixture.class))).isEmpty();
    }

    @Test
    void gateFailsOnLargeDropWithDetailedMessage() {
        updateBaseline(0.80);
        fixtureScore = 0.70; // 0.10 drop
        List<String> failures = containerFailures(run(GatedFixture.class));
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0))
                .contains("regression gate failed")
                .contains("Faithfulness")
                .contains("baseline 0.800")
                .contains("current 0.700");
    }

    @Test
    void missingBaselineFailsWithUpdateHint() {
        List<String> failures = containerFailures(run(GatedFixture.class));
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0))
                .contains("baseline file not found")
                .contains("-Deval4j.baseline.update=true");
    }

    @Test
    void baselineIsNeverWrittenWithoutTheUpdateFlag() {
        containerFailures(run(GatedFixture.class));
        assertThat(Files.exists(BASELINE)).isFalse();
    }

    @Test
    void reportAndHistoryWrittenOncePerRunAcrossClasses(@TempDir Path dir) throws Exception {
        System.setProperty(EvalReportExtension.REPORT_DIR_PROPERTY, dir.toString());
        run(PlainFixtureOne.class, PlainFixtureTwo.class);
        assertThat(dir.resolve("eval4j-report.json")).exists();
        assertThat(dir.resolve("eval4j-report.html")).exists();
        String json = Files.readString(dir.resolve("eval4j-report.json"));
        assertThat(json).contains("PlainFixtureOne").contains("PlainFixtureTwo");
        assertThat(Files.readAllLines(dir.resolve("eval4j-history.jsonl"))).hasSize(1);
        // a second run appends, and its HTML now has a trend for the metric
        run(PlainFixtureOne.class);
        assertThat(Files.readAllLines(dir.resolve("eval4j-history.jsonl"))).hasSize(2);
        assertThat(Files.readString(dir.resolve("eval4j-report.html"))).contains("<polyline");
    }

    @Test
    void noReportFilesWithoutTheProperty(@TempDir Path dir) throws Exception {
        run(PlainFixtureOne.class);
        assertThat(Files.list(dir).count()).isZero();
    }

    @Test
    void consolePrintsPerMetricAverages() {
        var captured = new java.io.ByteArrayOutputStream();
        var original = System.out;
        try {
            System.setOut(new java.io.PrintStream(captured));
            run(PlainFixtureOne.class);
        } finally {
            System.setOut(original);
        }
        assertThat(captured.toString()).contains("metric averages:").contains("M: 0.900");
    }

    @Test
    void recordedJudgeScoresFeedAggregation() {
        EvalRecorder.activate();
        EvalRecorder.record("M", 0.5, 0.5, "r", null);
        assertThat(BaselineGate.aggregate(EvalRecorder.records()))
                .containsEntry("null#null#M", 0.5);
        assertThat(Map.of()).isEmpty();
    }

    private static String read(String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (Exception e) {
            throw new AssertionError("could not read " + path, e);
        }
    }
}
