package io.github.llm4j.eval.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BaselineGateTest {

    private static final String SUITE = "S";

    private static EvalRecord rec(String test, String metric, double score) {
        return new EvalRecord(SUITE, test, metric, score, 0.5, score >= 0.5, "r", "j", "t");
    }

    @ParameterizedTest
    @CsvSource({
        "0.80, 0.80, true",
        "0.80, 0.76, true",
        "0.80, 0.75, true", // exactly the allowed drop passes (inclusive)
        "0.80, 0.74, false",
        "0.80, 0.90, true",
        "0.85, 0.80, true" // 0.85 - 0.80 is inexact in binary; must still be treated as 0.05
    })
    void suiteGate_boundaryTable(double baseline, double current, boolean shouldPass) {
        Map<String, Double> base = Map.of(SUITE + "#t#Faithfulness", baseline);
        Map<String, Double> now = Map.of(SUITE + "#t#Faithfulness", current);
        var result =
                BaselineGate.compare(base, now, SUITE, EvalBaseline.Granularity.SUITE, 0.05);
        assertThat(result.passed()).isEqualTo(shouldPass);
    }

    @Test
    void failureListsEveryRegressedMetricWithBaselineCurrentDelta() {
        Map<String, Double> base =
                Map.of(SUITE + "#t#A", 0.9, SUITE + "#t#B", 0.9, SUITE + "#t#C", 0.9);
        Map<String, Double> now =
                Map.of(SUITE + "#t#A", 0.5, SUITE + "#t#B", 0.9, SUITE + "#t#C", 0.7);
        var result = BaselineGate.compare(base, now, SUITE, EvalBaseline.Granularity.SUITE, 0.05);
        assertThat(result.regressions()).hasSize(2);
        assertThat(result.regressions().get(0))
                .contains("A")
                .contains("baseline 0.900")
                .contains("current 0.500")
                .contains("-0.400");
    }

    @Test
    void newMetricIsListedButNeverFails_removedMetricIgnored() {
        Map<String, Double> base = Map.of(SUITE + "#t#Old", 0.9);
        Map<String, Double> now = Map.of(SUITE + "#t#New", 0.1);
        var result = BaselineGate.compare(base, now, SUITE, EvalBaseline.Granularity.SUITE, 0.05);
        assertThat(result.passed()).isTrue();
        assertThat(result.noBaseline()).hasSize(1);
    }

    @Test
    void granularityChangesOutcome() {
        // two tests: one improves a lot, one regresses; the suite average is unchanged
        Map<String, Double> base = Map.of(SUITE + "#t1#M", 0.5, SUITE + "#t2#M", 0.9);
        Map<String, Double> now = Map.of(SUITE + "#t1#M", 0.9, SUITE + "#t2#M", 0.5);
        assertThat(
                        BaselineGate.compare(base, now, SUITE, EvalBaseline.Granularity.SUITE, 0.05)
                                .passed())
                .isTrue();
        assertThat(
                        BaselineGate.compare(base, now, SUITE, EvalBaseline.Granularity.CASE, 0.05)
                                .passed())
                .isFalse();
    }

    @Test
    void otherSuitesAreNotCompared() {
        Map<String, Double> base = Map.of("Other#t#M", 0.9);
        Map<String, Double> now = Map.of("Other#t#M", 0.1, SUITE + "#t#M", 0.5);
        var result = BaselineGate.compare(base, now, SUITE, EvalBaseline.Granularity.SUITE, 0.05);
        assertThat(result.passed()).isTrue();
    }

    @Test
    void aggregateAveragesRepeatedRecordsPerTestAndMetric() {
        Map<String, Double> agg =
                BaselineGate.aggregate(List.of(rec("t", "M", 0.5), rec("t", "M", 1.0), rec("u", "M", 0.2)));
        assertThat(agg).containsEntry(SUITE + "#t#M", 0.75).containsEntry(SUITE + "#u#M", 0.2);
    }

    @Test
    void updateWritesOnlyTheGivenSuiteAndKeepsOthers(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("baseline.json");
        BaselineGate.update(file, "Other", new TreeMap<>(Map.of("Other#t#M", 0.3)));
        BaselineGate.update(file, SUITE, new TreeMap<>(Map.of(SUITE + "#t#M", 0.8)));
        BaselineGate.update(file, SUITE, new TreeMap<>(Map.of(SUITE + "#t#N", 0.6)));
        Map<String, Double> loaded = BaselineGate.load(file);
        assertThat(loaded)
                .containsEntry("Other#t#M", 0.3)
                .containsEntry(SUITE + "#t#N", 0.6)
                .doesNotContainKey(SUITE + "#t#M");
        assertThat(Files.list(dir).count()).isEqualTo(1); // no stray temp files
    }
}
