package io.github.llm4j.loom.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A judge that compares two answers may favour the one shown first. Each pair is judged twice, the
 * second time with the answers swapped; a judge without position bias picks the same answer both
 * times.
 */
class PositionBiasTest {

    private static List<PositionBias.Pair> pairs(int consistent, int alwaysFirst, int alwaysSecond) {
        List<PositionBias.Pair> out = new ArrayList<>();
        for (int i = 0; i < consistent; i++) out.add(new PositionBias.Pair("c" + i, "A", "B")); // the same answer both times
        for (int i = 0; i < alwaysFirst; i++) out.add(new PositionBias.Pair("f" + i, "A", "A"));
        for (int i = 0; i < alwaysSecond; i++) out.add(new PositionBias.Pair("s" + i, "B", "B"));
        return out;
    }

    @Test
    void aJudgeThatPicksTheSameAnswerWhateverTheOrderIsNotBiased() {
        PositionBias.Result r = PositionBias.check(pairs(20, 0, 0), 10);

        assertThat(r.inconsistent()).isZero();
        assertThat(r.biased()).isFalse();
        assertThat(r.notes()).isEmpty();
    }

    @Test
    void aJudgeThatFollowsThePositionIsBiasedTowardsTheFirstOne() {
        PositionBias.Result r = PositionBias.check(pairs(10, 10, 0), 10);

        assertThat(r.inconsistent()).isEqualTo(10);
        assertThat(r.firstShare()).isGreaterThan(0.65);
        assertThat(r.biased()).isTrue();
        assertThat(String.join(" ", r.notes())).contains("changes its verdict when the order is swapped").contains("10 of 20").contains("first position");
    }

    @Test
    void aJudgeBiasedTowardsTheSecondPositionIsNamedToo() {
        PositionBias.Result r = PositionBias.check(pairs(10, 0, 10), 10);

        assertThat(r.biased()).isTrue();
        assertThat(String.join(" ", r.notes())).contains("second position");
    }

    @Test
    void aTieBothWaysIsConsistentAndOneTieAgainstAPickIsNot() {
        List<PositionBias.Pair> p = new ArrayList<>(pairs(12, 0, 0));
        p.add(new PositionBias.Pair("t1", "tie", "tie"));
        p.add(new PositionBias.Pair("t2", "tie", "A"));

        PositionBias.Result r = PositionBias.check(p, 10);

        assertThat(r.pairs()).isEqualTo(14);
        assertThat(r.inconsistent()).isEqualTo(1);
    }

    @Test
    void fewPairsAreNotEnoughToClearAJudge() {
        PositionBias.Result r = PositionBias.check(pairs(3, 0, 0), 10);

        assertThat(r.biased()).isTrue();
        assertThat(String.join(" ", r.notes())).contains("3 pairs").contains("at least 10");
    }

    @Test
    void aPickThatIsNotAOrBOrTieIsRefusedNamingThePair() {
        assertThatThrownBy(() -> PositionBias.check(List.of(new PositionBias.Pair("x1", "left", "B")), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("x1")
                .hasMessageContaining("A, B or tie");
    }

    @Test
    void theResultJoinsAValidationAndDeniesGatingWhenBiased() {
        JudgeValidation.Result base = new JudgeValidation.Result(40, 1.0, false, 1.0, 0, List.of(), List.of(), false, true);

        JudgeValidation.Result clean = base.withPosition(PositionBias.check(pairs(20, 0, 0), 10));
        JudgeValidation.Result biased = base.withPosition(PositionBias.check(pairs(10, 10, 0), 10));

        assertThat(base.usableForGating()).isTrue();
        assertThat(clean.usableForGating()).isTrue();
        assertThat(biased.usableForGating()).isFalse();
        assertThat(biased.summary()).contains("changes its verdict when the order is swapped").contains("not usable for gating");
    }

    @Test
    void anEmptyListIsNeverClearedAndAMinimumBelowOneIsRefused() {
        PositionBias.Result r = PositionBias.check(List.of(), 1);

        assertThat(r.biased()).isTrue();
        assertThat(String.join(" ", r.notes())).contains("0 pairs");
        assertThatThrownBy(() -> PositionBias.check(List.of(), 0)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least 1");
        assertThatThrownBy(() -> PositionBias.check(pairs(20, 0, 0), -3)).isInstanceOf(IllegalArgumentException.class);
    }
}
