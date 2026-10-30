package io.github.llm4j.loom.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase 19 R12: a judge is an instrument. Before a gate may use it, the humans are checked against
 * each other first (a rubric that people cannot agree on is the problem, not the model), and then
 * the judge against the humans' consensus, with quadratic-weighted Cohen's kappa.
 */
class JudgeValidationTest {

    private static JudgeValidation.Row row(String id, Integer judge, int... humans) {
        return new JudgeValidation.Row(id, humans, judge);
    }

    /** n rows whose three humans and the judge all follow {@code scores}. */
    private static List<JudgeValidation.Row> agreeing(int n, int... scores) {
        List<JudgeValidation.Row> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int s = scores[i % scores.length];
            out.add(row("c" + i, s, s, s, s));
        }
        return out;
    }

    @Test
    void identicalScoresGiveAKappaOfOne() {
        assertThat(JudgeValidation.kappa(new int[] {1, 2, 3, 4, 5}, new int[] {1, 2, 3, 4, 5})).isEqualTo(1.0);
    }

    @Test
    void agreementNoBetterThanChanceGivesAKappaNearZero() {
        double k = JudgeValidation.kappa(new int[] {1, 1, 5, 5}, new int[] {1, 5, 1, 5});

        assertThat(k).isLessThan(0.1);
    }

    @Test
    void aMissByOnePointCostsLessThanAMissByFourBecauseTheWeightsAreQuadratic() {
        int[] truth = {1, 2, 3, 4, 5, 3, 2, 4};
        int[] nearMiss = {2, 3, 3, 4, 5, 3, 2, 4};
        int[] farMiss = {5, 2, 3, 4, 5, 3, 2, 4};

        assertThat(JudgeValidation.kappa(truth, nearMiss)).isGreaterThan(JudgeValidation.kappa(truth, farMiss));
    }

    @Test
    void whenNobodyVariesAndEveryoneAgreesTheKappaIsOneNotNotANumber() {
        assertThat(JudgeValidation.kappa(new int[] {3, 3, 3}, new int[] {3, 3, 3})).isEqualTo(1.0);
    }

    @Test
    void theHumansAreCheckedFirstAndARubricTheyCannotShareStopsTheValidation() {
        List<JudgeValidation.Row> rows = new ArrayList<>();
        int[][] split = {{1, 5, 3}, {5, 1, 3}, {2, 4, 5}, {4, 2, 1}};
        for (int i = 0; i < 40; i++) {
            int[] h = split[i % split.length];
            rows.add(row("c" + i, 3, h));
        }

        JudgeValidation.Result r = JudgeValidation.validate(rows, 40);

        assertThat(r.rubricUnderSpecified()).isTrue();
        assertThat(r.judgeKappa()).isNull();
        assertThat(r.usableForGating()).isFalse();
        assertThat(r.summary()).contains("rubric is under-specified").contains("Rewrite the anchors");
    }

    @Test
    void aJudgeThatAgreesWithTheHumanConsensusIsUsableForGating() {
        JudgeValidation.Result r = JudgeValidation.validate(agreeing(40, 1, 2, 3, 4, 5), 40, "judge-m", "system-m");

        assertThat(r.interHumanKappa()).isEqualTo(1.0);
        assertThat(r.judgeKappa()).isEqualTo(1.0);
        assertThat(r.usableForGating()).isTrue();
        assertThat(r.meanBias()).isZero();
        assertThat(r.verdict()).isEqualTo("strong");
    }

    @Test
    void aJudgeThatIsOffByTwoOrMoreListsThoseCasesAndIsNotUsableBelowSixTenths() {
        List<JudgeValidation.Row> rows = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            int s = 1 + (i % 5);
            int judge = i % 2 == 0 ? Math.min(5, s + 2) : Math.max(1, s - 2);
            rows.add(row("c" + i, judge, s, s, s));
        }

        JudgeValidation.Result r = JudgeValidation.validate(rows, 40);

        assertThat(r.interHumanKappa()).isEqualTo(1.0);
        assertThat(r.judgeKappa()).isLessThan(0.6);
        assertThat(r.usableForGating()).isFalse();
        assertThat(r.offByTwoOrMore()).isNotEmpty();
        assertThat(r.summary()).contains("not usable for gating");
    }

    @Test
    void theMeanBiasSaysWhetherTheJudgeScoresHigherOrLowerThanThePeople() {
        List<JudgeValidation.Row> rows = new ArrayList<>();
        for (int i = 0; i < 40; i++) rows.add(row("c" + i, Math.min(5, 1 + i % 5 + 1), 1 + i % 5, 1 + i % 5, 1 + i % 5));

        assertThat(JudgeValidation.validate(rows, 40).meanBias()).isGreaterThan(0.5);
    }

    @Test
    void fewerThanTheMinimumNumberOfOutputsIsRefusedWithTheNumbers() {
        assertThatThrownBy(() -> JudgeValidation.validate(agreeing(12, 1, 2, 3), 40))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("12")
                .hasMessageContaining("at least 40");
    }

    @Test
    void aRowWithFewerThanTwoHumansCannotCheckTheHumansAgainstEachOther() {
        List<JudgeValidation.Row> rows = new ArrayList<>(agreeing(40, 1, 2, 3));
        rows.set(0, row("lonely", 3, 3));

        assertThatThrownBy(() -> JudgeValidation.validate(rows, 40))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lonely")
                .hasMessageContaining("two");
    }

    @Test
    void aRowWithoutAJudgeScoreIsSaidToBeMissingOneNotCountedAsAgreement() {
        List<JudgeValidation.Row> rows = new ArrayList<>(agreeing(40, 1, 2, 3));
        rows.set(5, row("c5", null, 2, 2, 2));

        assertThatThrownBy(() -> JudgeValidation.validate(rows, 40))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("c5")
                .hasMessageContaining("judge");
    }

    @Test
    void theBandsAreThePublishedOnes() {
        assertThat(JudgeValidation.band(0.3)).isEqualTo("poor");
        assertThat(JudgeValidation.band(0.5)).isEqualTo("moderate");
        assertThat(JudgeValidation.band(0.7)).isEqualTo("substantial");
        assertThat(JudgeValidation.band(0.9)).isEqualTo("strong");
    }
}
