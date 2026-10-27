package io.github.llm4j.loom.loop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.whatif.WhatIf;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** R22, R23, R26 of spec 14: how a replay is compared with the run it replays. */
class RunComparatorTest {

    private static RunRecord run(Map<String, RunJournal.Entry> journal) {
        return new RunRecord(
                "r",
                Path.of("/x/r"),
                Path.of("/x/main.loom"),
                "Main",
                Map.of(),
                "h",
                Instant.EPOCH,
                journal);
    }

    private static WhatIf.Outcome outcome(Object was, Object now, WhatIf.EffectDelta effects) {
        return new WhatIf.Outcome(
                "Main/s0",
                "x",
                List.of(),
                "recorded",
                null,
                null,
                null,
                was,
                now,
                null,
                effects,
                List.of(),
                "a",
                "b");
    }

    @Test
    void aRunIsGroupedByWhatItRecordedWithoutTheNumbersAroundIt() {
        Map<String, RunJournal.Entry> j = new LinkedHashMap<>();
        j.put(
                "Main/s0",
                new RunJournal.Entry(
                        "classify",
                        Map.of(
                                "kind",
                                "refund",
                                "kind_confidence",
                                0.9,
                                "urgent",
                                "no",
                                "urgent_probability",
                                0.1,
                                "confidence",
                                0.9,
                                "calibrated",
                                true,
                                "backend",
                                "x",
                                "model",
                                "m")));
        j.put("Main/s1", new RunJournal.Entry("human", "yes"));
        assertThat(RunComparator.stratum(run(j)))
                .isEqualTo("asked a person, kind=refund, urgent=no");
    }

    @Test
    void aFailureIsAStratumWhetherHandledOrNot() {
        assertThat(
                        RunComparator.stratum(
                                run(Map.of("Main/s1", new RunJournal.Entry("failed", "boom")))))
                .isEqualTo("failed");
        assertThat(
                        RunComparator.stratum(
                                run(
                                        Map.of(
                                                "Main/s1#failure",
                                                new RunJournal.Entry("failure", Map.of())))))
                .isEqualTo("failed");
        assertThat(RunComparator.stratum(run(Map.of()))).isEqualTo("no recorded choices");
        assertThat(
                        RunComparator.stratum(
                                run(Map.of("Main/s0", new RunJournal.Entry("delegate", "hi")))))
                .isEqualTo("no recorded choices");
    }

    @Test
    void aStratumListsEachPartOnceInOrder() {
        Map<String, RunJournal.Entry> j = new LinkedHashMap<>();
        j.put("Main/s0", new RunJournal.Entry("human", "a"));
        j.put("Main/s1", new RunJournal.Entry("human", "b"));
        j.put("Main/s2", new RunJournal.Entry("failed", "x"));
        assertThat(RunComparator.stratum(run(j))).isEqualTo("asked a person, failed");
    }

    @Test
    void aReWordedConditionIsTheSameStepButADifferentActionIsNot() {
        assertThat(RunComparator.shape("alt (a == 1)?")).isEqualTo("decision");
        assertThat(RunComparator.shape("loop until done")).isEqualTo("loop");
        assertThat(RunComparator.shape("delegate Replier")).isEqualTo("delegate Replier");
        assertThat(RunComparator.shape("loop until")).isEqualTo("loop");
        assertThat(RunComparator.shape("not a loop until")).isEqualTo("not a loop until");
    }

    @Test
    void minusCountsEachStepOnceAndKeepsTheOrder() {
        assertThat(RunComparator.minus(List.of("a", "b", "a", "c"), List.of("a", "c")))
                .containsExactly("b", "a");
        assertThat(RunComparator.minus(List.of("a"), List.of("a", "a"))).isEmpty();
        assertThat(RunComparator.minus(List.of("a", "a"), List.of("a"))).containsExactly("a");
        assertThat(RunComparator.minus(List.of(), List.of("a"))).isEmpty();
        assertThat(RunComparator.minus(List.of("x"), List.of())).containsExactly("x");
    }

    @Test
    void theFirstDifferenceNamesTheStepNumberAndBothSteps() {
        assertThat(RunComparator.firstDifference(List.of("a", "b", "c"), List.of("a", "x", "c")))
                .isEqualTo("the run took \"b\" at step 2, the candidate takes \"x\"");
        assertThat(RunComparator.firstDifference(List.of("a", "b"), List.of("z", "b")))
                .isEqualTo("the run took \"a\" at step 1, the candidate takes \"z\"");
        assertThat(RunComparator.firstDifference(List.of("a", "b"), List.of("a", "b"))).isNull();
        assertThat(RunComparator.firstDifference(List.of(), List.of())).isNull();
    }

    @Test
    void aRouteThatEndsEarlyOrGoesOnIsSaidSo() {
        assertThat(RunComparator.firstDifference(List.of("a", "b", "c"), List.of("a", "b")))
                .isEqualTo("the candidate ends after 2 steps; the run went on to \"c\"");
        assertThat(RunComparator.firstDifference(List.of("a"), List.of("a", "b")))
                .isEqualTo("the candidate goes on to \"b\" after the run's last step");
    }

    @Test
    void aPersonNoLongerAskedIsCountedByHowManySteps() {
        assertThat(RunComparator.humanRemoved(List.of("ask a person", "delegate X"), List.of()))
                .containsExactly("a person is no longer asked (1 step(s))");
        assertThat(
                        RunComparator.humanRemoved(
                                List.of("ask a person", "human_prompt \"ok?\""),
                                List.of("ask a person")))
                .containsExactly("a person is no longer asked (1 step(s))");
        assertThat(RunComparator.humanRemoved(List.of("ask a person"), List.of("ask a person")))
                .isEmpty();
        assertThat(RunComparator.humanRemoved(List.of(), List.of("ask a person"))).isEmpty();
        assertThat(RunComparator.humanRemoved(List.of("delegate X"), List.of())).isEmpty();
        assertThat(RunComparator.asksAPerson("ask a person")).isTrue();
        assertThat(RunComparator.asksAPerson("human_prompt \"x\"")).isTrue();
        assertThat(RunComparator.asksAPerson("delegate Human")).isFalse();
    }

    @Test
    void anEqualResultNeverChangesAndATypedOneAlwaysDoes() throws Exception {
        assertThat(RunComparator.resultChanged(outcome("a", "a", null), "exact", null)).isFalse();
        assertThat(RunComparator.resultChanged(outcome(null, null, null), "exact", null)).isFalse();
        assertThat(
                        RunComparator.resultChanged(
                                outcome(Map.of("k", 1), Map.of("k", 2), null), "none", null))
                .isTrue();
        assertThat(
                        RunComparator.resultChanged(
                                outcome("{\"k\": 1}", "{\"k\": 2}", null), "none", null))
                .isTrue();
        assertThat(RunComparator.resultChanged(outcome("plain", "{\"k\": 2}", null), "none", null))
                .isTrue();
        assertThat(RunComparator.resultChanged(outcome("{\"k\": 2}", "plain", null), "none", null))
                .isTrue();
    }

    @Test
    void textIsComparedByTheJudgeWhenThereIsOneAndOtherwiseByTheModeChosen() throws Exception {
        assertThat(RunComparator.resultChanged(outcome("a", "b", null), "exact", null)).isTrue();
        assertThat(RunComparator.resultChanged(outcome("a", "b", null), "label", null)).isTrue();
        assertThat(RunComparator.resultChanged(outcome("a", "b", null), "none", null)).isFalse();
        assertThat(RunComparator.resultChanged(outcome("a", "b", null), "exact", (x, y) -> false))
                .isFalse();
        assertThat(RunComparator.resultChanged(outcome("a", "b", null), "none", (x, y) -> true))
                .isTrue();
        // a missing answer is not given to the judge
        assertThat(RunComparator.resultChanged(outcome("a", null, null), "none", (x, y) -> true))
                .isFalse();
        assertThat(RunComparator.resultChanged(outcome(null, "b", null), "none", (x, y) -> true))
                .isFalse();
    }

    @Test
    void onlyAnObjectShapedTextLooksTyped() {
        assertThat(RunComparator.looksTyped("  {\"a\":1}  ")).isTrue();
        assertThat(RunComparator.looksTyped("{}")).isTrue();
        assertThat(RunComparator.looksTyped("{")).isFalse();
        assertThat(RunComparator.looksTyped("{a")).isFalse();
        assertThat(RunComparator.looksTyped("a}")).isFalse();
        assertThat(RunComparator.looksTyped(7)).isFalse();
        assertThat(RunComparator.looksTyped(null)).isFalse();
    }

    @Test
    void aNewEffectIsUnsafeAndNothingElseIs() {
        assertThat(UnsafeFlip.reasons(outcome("a", "a", null))).isEmpty();
        WhatIf.Effect e = new WhatIf.Effect("Main/s2", "run IssueRefund", "a tool");
        assertThat(
                        UnsafeFlip.reasons(
                                outcome(
                                        "a",
                                        "a",
                                        new WhatIf.EffectDelta(List.of(e), List.of(), false))))
                .isEmpty();
        assertThat(
                        UnsafeFlip.reasons(
                                outcome(
                                        "a",
                                        "a",
                                        new WhatIf.EffectDelta(List.of(), List.of(e), true))))
                .containsExactly("NEW EFFECT: run IssueRefund reaches a tool");
    }
}
