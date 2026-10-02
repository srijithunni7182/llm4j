package io.github.llm4j.loom.autonomy;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.DecisionDef;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The statistics on their own (spec loom-earned-autonomy R4.5). */
class AgreementStatsTest {

    @Test
    @Tag("EA-V4.5")
    void theWilsonLowerBoundMatchesPublishedReferenceValues() {
        assertThat(AgreementStats.wilsonLower(0, 0)).isZero();
        assertThat(AgreementStats.wilsonLower(20, 20)).isCloseTo(0.838875, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(AgreementStats.wilsonLower(90, 100)).isCloseTo(0.825634, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(AgreementStats.wilsonLower(291, 300)).isCloseTo(0.943977, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(AgreementStats.wilsonLower(0, 10)).isZero();
        assertThat(AgreementStats.wilsonLower(5, 10)).isCloseTo(0.236593, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(AgreementStats.wilsonLower(1, 2)).isCloseTo(0.094531, org.assertj.core.data.Offset.offset(1e-6));
    }

    /** Wilson by the textbook formula, written differently, as the reference. */
    private static double reference(int k, int n) {
        if (n == 0) return 0;
        double p = (double) k / n, z = 1.959964;
        double denominator = 1 + z * z / n;
        double a = (p + z * z / (2 * n)) / denominator;
        double b = z * Math.sqrt((p * (1 - p) + z * z / (4.0 * n)) / n) / denominator;
        return Math.max(0, a - b);
    }

    @Test
    @Tag("EA-V4.5")
    void theBoundIsBelowTheRateGrowsWithAgreementsAndWithCasesAndAgreesWithAReference() {
        Random random = new Random(7);
        for (int i = 0; i < 10_000; i++) {
            int n = 1 + random.nextInt(400);
            int k = random.nextInt(n + 1);
            double bound = AgreementStats.wilsonLower(k, n);
            assertThat(bound).isLessThanOrEqualTo((double) k / n + 1e-12).isGreaterThanOrEqualTo(0);
            assertThat(bound).isCloseTo(reference(k, n), org.assertj.core.data.Offset.offset(1e-9));
            if (k < n) assertThat(AgreementStats.wilsonLower(k + 1, n)).isGreaterThan(bound);
        }
        for (int m = 1; m < 60; m++) {
            assertThat(AgreementStats.wilsonLower(9 * (m + 1), 10 * (m + 1))).as("the same rate over more cases").isGreaterThan(AgreementStats.wilsonLower(9 * m, 10 * m));
        }
    }

    @Test
    @Tag("EA-V4.3")
    void figuresCountAgreementDangerousMistakesCoverageMalformedAndReversals() {
        List<DecisionDef.Mistake> dangerous = List.of(new DecisionDef.Mistake("approve", "reject"));
        List<AgreementStats.Sample> samples = new ArrayList<>(List.of(
                new AgreementStats.Sample("approve", "approve"),
                new AgreementStats.Sample("approve", "reject"),            // dangerous
                new AgreementStats.Sample("reject", "approve"),            // wrong, not dangerous
                new AgreementStats.Sample("escalate", "approve"),          // escalating is its own choice: disagreement, no coverage
                new AgreementStats.Sample("escalate", "reject", true, false),
                new AgreementStats.Sample("approve", "approve", false, true)));

        AgreementStats.Figures f = AgreementStats.of(samples, dangerous);

        assertThat(f.cases()).isEqualTo(6);
        assertThat(f.matches()).isEqualTo(2);
        assertThat(f.rate()).isEqualTo(2.0 / 6);
        assertThat(f.dangerous()).isEqualTo(1);
        assertThat(f.dangerousRate()).isEqualTo(1.0 / 6);
        assertThat(f.coverage()).isEqualTo(4.0 / 6);
        assertThat(f.malformed()).isEqualTo(1);
        assertThat(f.reversals()).isEqualTo(1);
        assertThat(f.lowerBound()).isEqualTo(AgreementStats.wilsonLower(2, 6));
        assertThat(AgreementStats.of(List.of(), dangerous)).isEqualTo(AgreementStats.Figures.EMPTY);
    }

    @Test
    @Tag("EA-V4.3")
    void anAgentThatAlwaysEscalatesIsRightOnlyWhenPeopleEscalateAndCoversNothing() {
        List<AgreementStats.Sample> samples = new ArrayList<>();
        for (int i = 0; i < 20; i++) samples.add(new AgreementStats.Sample("escalate", "approve"));
        AgreementStats.Figures f = AgreementStats.of(samples, List.of());
        assertThat(f.rate()).isZero();
        assertThat(f.coverage()).isZero();
        samples.add(new AgreementStats.Sample("escalate", "escalate"));
        assertThat(AgreementStats.of(samples, List.of()).matches()).isEqualTo(1);
    }
}
