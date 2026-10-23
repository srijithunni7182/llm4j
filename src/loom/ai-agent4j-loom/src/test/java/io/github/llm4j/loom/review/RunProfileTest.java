package io.github.llm4j.loom.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.review.cost.CostEstimate;
import io.github.llm4j.loom.review.cost.CostEstimator;
import io.github.llm4j.loom.review.cost.EffectsEstimate;
import io.github.llm4j.loom.review.cost.TimeEstimate;
import io.github.llm4j.loom.review.cost.WorkflowCost;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R35 and R36 of spec 09: how long a workflow takes and what it can do, from the script alone. */
class RunProfileTest {

    @TempDir Path dir;

    static final String SMALL = "agent S { model: \"claude-haiku\"  system: \"s\" }\n";
    static final String STD = "agent T { model: \"m\"  system: \"s\" }\n";
    static final String BIG = "agent B { model: \"claude-opus\"  system: \"s\" }\n";
    static final String MAILER =
            "tool Mail { use: webhook  url: env.HOOK }\n"
                    + "agent N { model: \"m\"  system: \"s\"  tools: [Mail] }\n";

    WorkflowCost main(String source, String properties) {
        CostEstimate e =
                new CostEstimator()
                        .estimate(
                                ReviewFixtures.input(dir, source),
                                ReviewSettings.parse("review.properties", properties));
        return e.workflows().get(0);
    }

    WorkflowCost main(String source) {
        return main(source, "");
    }

    TimeEstimate time(String source) {
        return main(source).time();
    }

    @Test
    void aModelCallTakesTheLatencyOfItsTier() {
        assertThat(time(SMALL + "workflow Main(q) { delegate \"{q}\" to S -> r }").typicalMillis())
                .isEqualTo(2000);
        assertThat(time(STD + "workflow Main(q) { delegate \"{q}\" to T -> r }").typicalMillis())
                .isEqualTo(5000);
        assertThat(time(BIG + "workflow Main(q) { delegate \"{q}\" to B -> r }").typicalMillis())
                .isEqualTo(15000);
    }

    @Test
    void stepsInSequenceAddUp() {
        TimeEstimate t =
                time(
                        STD
                                + "workflow Main(q) { delegate \"{q}\" to T -> a  delegate \"{a}\" to T -> b }");
        assertThat(t.typicalMillis()).isEqualTo(10000);
        assertThat(t.worstMillis()).isEqualTo(10000);
        assertThat(t.criticalPath()).hasSize(2);
    }

    @Test
    void parallelBranchesTakeTheLongestOneNotTheSum() {
        TimeEstimate t =
                time(
                        SMALL
                                + BIG
                                + "workflow Main(q) { parallel { delegate \"{q}\" to S -> a  delegate \"{q}\" to B -> b } }");
        assertThat(t.typicalMillis()).isEqualTo(15000);
        assertThat(t.criticalPath()).singleElement().asString().contains("delegate B");
        assertThat(
                        time(SMALL + BIG + "workflow Main(q) { broadcast \"{q}\" to [S, B] -> r }")
                                .typicalMillis())
                .isEqualTo(15000);
    }

    @Test
    void anAgentWithToolsMakesSeveralRoundsEachWithAToolCall() {
        WorkflowCost w = main(MAILER + "workflow Main(q) { delegate \"{q}\" to N -> r }");
        // typical 3 rounds of (5 s model + 2 s tool); worst 10 rounds
        assertThat(w.time().typicalMillis()).isEqualTo(21000);
        assertThat(w.time().worstMillis()).isEqualTo(70000);
    }

    @Test
    void aRetryAddsItsAttemptsAndBackoffInTheWorstCase() {
        TimeEstimate t =
                time(STD + "workflow Main(q) { delegate \"{q}\" to T -> r retry 2 backoff 2s }");
        assertThat(t.typicalMillis()).isEqualTo(5000);
        assertThat(t.worstMillis()).isEqualTo(3 * 5000 + 2 * 2000);
    }

    @Test
    void aTimeoutCapsEachAttempt() {
        TimeEstimate t = time(STD + "workflow Main(q) { delegate \"{q}\" to T -> r timeout 3s }");
        assertThat(t.worstMillis()).isEqualTo(3000);
    }

    @Test
    void aBoundedLoopMultipliesAndAnUnboundedOneIsUnbounded() {
        TimeEstimate bounded =
                time(
                        STD
                                + "workflow Main(q) { loop until (x == \"y\") max 4 { delegate \"{q}\" to T -> x } }");
        assertThat(bounded.typicalMillis()).isEqualTo(3 * 5000);
        assertThat(bounded.worstMillis()).isEqualTo(4 * 5000);
        assertThat(bounded.unbounded()).isFalse();
        TimeEstimate open =
                time(
                        STD
                                + "workflow Main(q) { loop until (x == \"y\") { delegate \"{q}\" to T -> x } }");
        assertThat(open.unbounded()).isTrue();
        assertThat(open.worstMillis()).isEqualTo(5000); // the part that has a bound
    }

    @Test
    void aLoopThatTakesNoTimeIsNotUnbounded() {
        TimeEstimate t =
                time(STD + "workflow Main(q) { loop until (x == \"y\") { note \"waiting\" } }");
        assertThat(t.unbounded()).isFalse();
    }

    @Test
    void parallelForEachOverlapsItsItemsButSequentialDoesNot() {
        String body = "{ delegate \"{item}\" to T -> r }";
        TimeEstimate seq =
                time(STD + "workflow Main(items) { for each item in items " + body + " }");
        TimeEstimate par =
                time(STD + "workflow Main(items) { parallel for each item in items " + body + " }");
        assertThat(seq.typicalMillis()).isEqualTo(5 * 5000);
        assertThat(par.typicalMillis()).isEqualTo(5000);
    }

    @Test
    void anAltIsTheAverageTypicallyAndTheLongerWayAtWorst() {
        TimeEstimate t =
                time(
                        SMALL
                                + BIG
                                + "workflow Main(q) { alt (q == \"a\") { delegate \"{q}\" to S -> r } else { delegate \"{q}\" to B -> r } }");
        assertThat(t.typicalMillis()).isEqualTo((2000 + 15000) / 2);
        assertThat(t.worstMillis()).isEqualTo(15000);
    }

    @Test
    void waitsForPeopleAndTimersAreListedNotAdded() {
        TimeEstimate t =
                time(
                        STD
                                + "workflow Main(q) {\n  human_prompt \"ok?\" -> ok\n  sleep 1h\n  delegate \"{q}\" to T -> r\n}");
        assertThat(t.typicalMillis()).isEqualTo(5000);
        assertThat(t.waits())
                .anyMatch(w -> w.startsWith("human_prompt"))
                .anyMatch(w -> w.startsWith("sleep"));
        assertThat(t.sleepMillis()).isEqualTo(3_600_000);
    }

    @Test
    void theLatenciesAreAssumptionsAProjectCanChange() {
        TimeEstimate t =
                main(
                                STD + "workflow Main(q) { delegate \"{q}\" to T -> r }",
                                "assume.latency.standard = 9\n")
                        .time();
        assertThat(t.typicalMillis()).isEqualTo(9000);
        assertThat(t.assumptions()).containsEntry("assume.latency.standard", 9L);
    }

    // ---- effects

    @Test
    void anAgentWithASendingToolCountsASendPerCallAndPerRound() {
        EffectsEstimate e =
                main(MAILER + "workflow Main(q) { delegate \"{q}\" to N -> r }").effects();
        assertThat(e.sends().typical()).isEqualTo(1);
        assertThat(e.sends().worst()).isEqualTo(10); // the agent's rounds at most
        assertThat(e.writes().none()).isTrue();
    }

    @Test
    void aRetryRepeatsTheEffectInTheWorstCase() {
        EffectsEstimate e =
                main(MAILER + "workflow Main(q) { delegate \"{q}\" to N -> r retry 2 }").effects();
        assertThat(e.sends().typical()).isEqualTo(1);
        assertThat(e.sends().worst()).isEqualTo(30); // 3 attempts x 10 rounds
    }

    @Test
    void loopsMultiplyEffectsAndAnUnboundedLoopIsUnbounded() {
        EffectsEstimate bounded =
                main(MAILER
                                + "workflow Main(q) { loop until (x == \"y\") max 2 { delegate \"{q}\" to N -> x } }")
                        .effects();
        assertThat(bounded.sends().typical()).isEqualTo(2);
        assertThat(bounded.sends().worst()).isEqualTo(20);
        EffectsEstimate open =
                main(MAILER
                                + "workflow Main(q) { loop until (x == \"y\") { delegate \"{q}\" to N -> x } }")
                        .effects();
        assertThat(open.sends().unbounded()).isTrue();
    }

    @Test
    void aReadOnlyOrToollessWorkflowHasNoEffects() {
        assertThat(main(STD + "workflow Main(q) { delegate \"{q}\" to T -> r }").effects().none())
                .isTrue();
    }
}
