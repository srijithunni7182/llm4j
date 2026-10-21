package io.github.llm4j.eval.compare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.github.llm4j.classify.ClassifyUsage;
import io.github.llm4j.eval.compare.ClassifierComparison.Case;
import io.github.llm4j.eval.testing.ScriptedClassifier;
import io.github.llm4j.exception.RateLimitException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The report maths of {@link ClassifierComparison}, checked by hand on small scripted backends (no model is called). */
class ClassifierComparisonTest {

    private static final List<Case> CASES = List.of(
            Case.yesNo("c1", "easy", "one", "Is it good?", true),
            Case.yesNo("c2", "easy", "two", "Is it good?", true),
            Case.yesNo("c3", "hard", "three", "Is it good?", true),
            Case.yesNo("c4", "hard", "four", "Is it good?", false));

    /** Answers 0.95, 0.85, 0.25, 0.05 to the four cases: right, right, wrong (leans no, confidence 0.75), right. */
    private static ScriptedClassifier a() {
        return ScriptedClassifier.builder().usage(new ClassifyUsage(100, 10, 200, "jev", "jev-1"))
                .yesNo("criterion", 0.95).next().yesNo("criterion", 0.85).next().yesNo("criterion", 0.25).next().yesNo("criterion", 0.05).build();
    }

    /** Leans yes, no, no, no: agrees with A on three of four cases. */
    private static ScriptedClassifier b() {
        return ScriptedClassifier.builder().usage(new ClassifyUsage(100, 10, 1000, "llm", "m"))
                .yesNo("criterion", 0.9).next().yesNo("criterion", 0.2).next().yesNo("criterion", 0.2).next().yesNo("criterion", 0.2).build();
    }

    private static ClassifierComparison.Report run() {
        return ClassifierComparison.builder().cases(CASES).parallelism(1).contender("A", a()).contender("B", b()).price("B", 1.0, 2.0).build().run();
    }

    private static ClassifierComparison.Row row(ClassifierComparison.Report r, String name) {
        return r.rows().stream().filter(x -> x.contender().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void accuracyIsAgreementWithTheLabels() {
        assertThat(row(run(), "A").accuracy()).isEqualTo(0.75);
        assertThat(row(run(), "B").accuracy()).isEqualTo(0.5);   // right on c1 and c4 only: c2 and c3 lean no but are labelled yes
    }

    @Test
    void calibrationErrorAndBrierAreComputedFromConfidenceAndCorrectness() {
        ClassifierComparison.Row a = row(run(), "A");

        // bins: [0.9,1.0) two right at 0.95 -> |1-0.95|*2/4; [0.8,0.9) one right at 0.85 -> 0.15/4; [0.7,0.8) one wrong at 0.75 -> 0.75/4
        assertThat(a.ece().getAsDouble()).isCloseTo(0.25, within(1e-9));
        assertThat(a.brier().getAsDouble()).isCloseTo(0.1475, within(1e-9));
    }

    @Test
    void selectiveAccuracyKeepsTheMostConfidentShare() {
        ClassifierComparison.Row a = row(run(), "A");

        assertThat(a.accuracyAt80().getAsDouble()).isEqualTo(1.0);   // the three most confident answers are all right
        assertThat(a.accuracyAt60().getAsDouble()).isEqualTo(1.0);
    }

    @Test
    void agreementIsTheShareOfCasesWithTheSamePrediction() {
        assertThat(run().agreement()).containsEntry("A vs B", 0.75);
    }

    @Test
    void accuracyIsBrokenDownByCategory() {
        ClassifierComparison.Report r = run();

        assertThat(r.accuracyByCategory().get("A")).containsEntry("easy", 1.0).containsEntry("hard", 0.5);
        assertThat(r.accuracyByCategory().get("B")).containsEntry("easy", 0.5).containsEntry("hard", 0.5);
    }

    @Test
    void latencyTokensAndCostComeFromTheReportedUsage() {
        ClassifierComparison.Report r = run();

        assertThat(row(r, "A").p50Millis()).isEqualTo(200);
        assertThat(row(r, "B").p95Millis()).isEqualTo(1000);
        assertThat(row(r, "A").inputTokens()).isEqualTo(400);
        assertThat(row(r, "A").outputTokens()).isEqualTo(40);
        assertThat(row(r, "A").costPer1000()).isEmpty();                          // no price given
        assertThat(row(r, "B").costPer1000().getAsDouble()).isCloseTo(0.12, within(1e-9));   // (100*1 + 10*2) / 1e6 per call, times 1000
    }

    @Test
    void theCalibrationCurveHasOneBinPerPopulatedConfidenceRange() {
        List<ClassifierComparison.Bin> curve = run().curves().get("A");

        assertThat(curve).hasSize(3);
        ClassifierComparison.Bin top = curve.get(curve.size() - 1);
        assertThat(top.low()).isCloseTo(0.9, within(1e-9));
        assertThat(top.n()).isEqualTo(2);
        assertThat(top.accuracy()).isEqualTo(1.0);
        assertThat(top.meanConfidence()).isCloseTo(0.95, within(1e-9));
    }

    @Test
    void aFailingCallIsInvalidAndCountsAsWrongButDoesNotStopTheRun() {
        ScriptedClassifier flaky = ScriptedClassifier.builder().yesNo("criterion", 0.9).next().failWith(new RateLimitException("slow")).next()
                .yesNo("criterion", 0.9).next().yesNo("criterion", 0.1).build();

        ClassifierComparison.Row r = row(ClassifierComparison.builder().cases(CASES).parallelism(1).contender("F", flaky).build().run(), "F");

        assertThat(r.invalid()).isEqualTo(1);
        assertThat(r.accuracy()).isEqualTo(0.75);
    }

    @Test
    void repeatsMeasureHowOftenTheAnswerFlips() {
        // two repeats over two cases: the first case answers yes then no; the second answers yes both times
        ScriptedClassifier flipper = ScriptedClassifier.builder().yesNo("criterion", 0.9).next().yesNo("criterion", 0.9).next().yesNo("criterion", 0.1).next()
                .yesNo("criterion", 0.9).build();

        ClassifierComparison.Report r = ClassifierComparison.builder().cases(CASES.subList(0, 2)).repeats(2).parallelism(1).contender("X", flipper).build().run();

        // repeat order is case-major: (c1 r0)=yes, (c1 r1)=yes, (c2 r0)=no, (c2 r1)=yes  -> c2 flips
        assertThat(r.flipRate().get("X")).isEqualTo(0.5);
    }

    @Test
    void choiceCasesWorkAndUseTheLabelsProbabilityAsConfidence() {
        Case c = Case.choice("k1", "intent", "where is my parcel", "What does the customer want?", "order_status", "refund", "order_status", "other");
        ScriptedClassifier right = ScriptedClassifier.builder().choice("criterion", "order_status", 0.8).build();

        ClassifierComparison.Row r = row(ClassifierComparison.builder().cases(List.of(c)).contender("R", right).build().run(), "R");

        assertThat(r.accuracy()).isEqualTo(1.0);
        assertThat(r.brier().getAsDouble()).isCloseTo(0.04, within(1e-9));
    }

    @Test
    void theMarkdownReportNamesEveryContenderAndMetric() {
        String md = run().toMarkdown();

        assertThat(md).contains("| A |").contains("| B |").contains("accuracy").contains("ECE").contains("Brier").contains("A vs B").contains("easy")
                .contains("0.750").contains("0.250");
    }

    @Test
    void aSetupMistakeIsRefused() {
        assertThatThrownBy(() -> ClassifierComparison.builder().cases(CASES).build()).hasMessageContaining("contender");
        assertThatThrownBy(() -> ClassifierComparison.builder().contender("A", a()).build()).hasMessageContaining("case");
        assertThatThrownBy(() -> ClassifierComparison.builder().contender("A", a()).contender("A", b())).hasMessageContaining("twice");
        assertThatThrownBy(() -> ClassifierComparison.builder().repeats(0)).hasMessageContaining("repeats");
        assertThatThrownBy(() -> Case.choice("x", "c", "t", "q?", "zzz", "a", "b")).hasMessageContaining("zzz");
    }
}
