package io.github.llm4j.loom.children;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.SuperviseStmt;
import io.github.llm4j.loom.execution.JudgeSchedule;
import io.github.llm4j.loom.execution.JudgeSchedule.Decision;
import org.junit.jupiter.api.Test;

/** Guard G13 of phase 33 (D26): a judge is asked on a cadence, at most {@code max} times, and not when nothing happened. */
class JudgeScheduleTest {

    private static SuperviseStmt.Judged rule(String cadence, int max) {
        SuperviseStmt block =
                (SuperviseStmt)
                        StartParseTest.parse(
                                        "workflow W(q) { supervise work budget { tokens: 5 } { assert \"on topic\" using J every "
                                                + cadence
                                                + " max "
                                                + max
                                                + " { wrap_up } } }")
                                .getWorkflows()
                                .get(0)
                                .getStatements()
                                .get(0);
        return block.getJudged().get(0);
    }

    private static final long MIN = 60_000;

    @Test
    void theFirstJudgementComesAfterTheFirstIntervalNotBefore() {
        var schedule = new JudgeSchedule(rule("2m", 4), 0);

        assertThat(schedule.decide(MIN, 3, 11)).isEqualTo(Decision.NOT_YET);
        assertThat(schedule.decide(2 * MIN, 3, 11)).isEqualTo(Decision.ASK);
    }

    @Test
    void aJudgementIsSkippedWhenTheChildHasDoneNothingSinceTheLast() {
        var schedule = new JudgeSchedule(rule("1m", 4), 7);

        assertThat(schedule.decide(MIN, 5, 7)).as("nothing since the start").isEqualTo(Decision.SKIP_NO_PROGRESS);
        assertThat(schedule.decide(MIN, 5, 8)).isEqualTo(Decision.ASK);
        schedule.asked(MIN, 5, 8);
        assertThat(schedule.decide(2 * MIN, 5, 8)).as("nothing since the last").isEqualTo(Decision.SKIP_NO_PROGRESS);
        assertThat(schedule.decide(2 * MIN, 6, 9)).isEqualTo(Decision.ASK);
    }

    @Test
    void theCeilingStopsTheJudgeAndSaysSoOnce() {
        var schedule = new JudgeSchedule(rule("1m", 2), 0);
        schedule.asked(MIN, 1, 1);
        schedule.asked(2 * MIN, 2, 2);

        assertThat(schedule.decide(3 * MIN, 3, 3)).isEqualTo(Decision.LIMIT_REACHED);
        assertThat(schedule.decide(4 * MIN, 4, 4)).isEqualTo(Decision.STOPPED);
    }

    @Test
    void aCadenceInStepsCountsTheChildsJournaledSteps() {
        var schedule = new JudgeSchedule(rule("3 steps", 4), 0);

        assertThat(schedule.decide(10 * MIN, 2, 5)).isEqualTo(Decision.NOT_YET);
        assertThat(schedule.decide(10 * MIN, 3, 6)).isEqualTo(Decision.ASK);
        schedule.asked(10 * MIN, 3, 6);
        assertThat(schedule.decide(10 * MIN, 5, 8)).isEqualTo(Decision.NOT_YET);
        assertThat(schedule.decide(10 * MIN, 6, 9)).isEqualTo(Decision.ASK);
    }

    @Test
    void judgementsAlreadyMadeBeforeARestartCountTowardsTheCeiling() {
        var schedule = new JudgeSchedule(rule("1m", 2), 0, 2);

        assertThat(schedule.decide(5 * MIN, 5, 5)).isEqualTo(Decision.LIMIT_REACHED);
    }

    @Test
    void theJudgeBudgetUsedUpStopsJudgedRulesButNotTheOthers() {
        var schedule = new JudgeSchedule(rule("1m", 4), 0);
        schedule.budgetUsedUp();

        assertThat(schedule.decide(5 * MIN, 5, 5)).isEqualTo(Decision.BUDGET_USED_UP);
        assertThat(schedule.decide(6 * MIN, 6, 6)).isEqualTo(Decision.STOPPED);
    }

    @Test
    void itCountsHowManyTimesTheJudgeWasAsked() {
        var schedule = new JudgeSchedule(rule("1m", 4), 0, 1);
        assertThat(schedule.askedCount()).isEqualTo(1);
        schedule.asked(MIN, 1, 1);
        assertThat(schedule.askedCount()).isEqualTo(2);
    }
}
