package io.github.llm4j.loom.autonomy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.runtime.RunSuspended;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A ladder climbed and descended by running cases, not by seeding the ledger (spec loom-earned-autonomy R3). */
class LadderRunTest {

    @TempDir
    Path dir;

    private DecideHarness harness(String trustExtra) {
        return new DecideHarness(dir, new MemoryLedger(), new MemoryLevelStore(), Scripts2.refund(trustExtra));
    }

    private static void cases(DecideHarness h, String scope, int n, int from) {
        for (int i = 0; i < n; i++) {
            h.runCase("run-" + scope + (from + i), h.inputs(scope, 10 + from + i));
            h.clock.advance(java.time.Duration.ofHours(1));
        }
    }

    private Level level(DecideHarness h, String scope) {
        return h.levels.get("Refund", scope).orElseThrow().level();
    }

    @Test
    @Tag("EA-V3.9")
    void autoPromotionMovesTheLevelAtTheThresholdAndTheNextCaseRunsAtTheNewLevel() {
        DecideHarness h = harness("");
        cases(h, "gold", 4, 0);
        assertThat(level(h, "gold")).isEqualTo(Level.WATCH);
        cases(h, "gold", 1, 4);
        assertThat(level(h, "gold")).as("after the 5th agreeing blind case").isEqualTo(Level.SUGGEST);
        assertThat(h.audit).contains("level_changed");
        assertThat(h.ledger.records("Refund").stream().filter(r -> Rec.LEVEL.equals(r.kind())).map(r -> r.str("to")).toList()).containsSubsequence("watch", "suggest");

        h.asked.clear();
        Map<String, Object> next = h.runCase("run-gold-next", h.inputs("gold", 99));
        assertThat(next.get("verdict_level")).isEqualTo("suggest");
        assertThat(h.asked.get(0)).contains("The agent proposes");
    }

    @Test
    @Tag("EA-V3.9")
    void approvalMakesOneProposalMovesNothingUntilApprovedAndRecordsTheApprover() {
        DecideHarness h = harness("").also(x -> x.script = Scripts2.refund("").replace("moving up is automatic", "moving up needs approval from: risk-owner"));
        cases(h, "gold", 5, 0);
        assertThat(level(h, "gold")).as("earned, not approved").isEqualTo(Level.WATCH);
        cases(h, "gold", 2, 5);
        assertThat(h.ledger.records("Refund").stream().filter(r -> Rec.PROMOTION_PROPOSED.equals(r.kind()))).hasSize(1);
        assertThat(h.audit).contains("promotion_proposed").doesNotContain("level_changed");

        Engine engine = new Engine(DecisionParseTest.parse(h.script).getDecisions().get(0), h.ledger, h.levels, h.clock);
        assertThatThrownBy(() -> engine.approve("gold", "somebody-else", "ok")).hasMessageContaining("only risk-owner");
        Engine.Change change = engine.approve("gold", "risk-owner", "the numbers look right");
        assertThat(change.to()).isEqualTo(Level.SUGGEST);
        assertThat(level(h, "gold")).isEqualTo(Level.SUGGEST);
        assertThat(engine.ladder().proposals("gold").get(0).by()).isEqualTo("risk-owner");
        assertThatThrownBy(() -> engine.approve("gold", "risk-owner", "again")).hasMessageContaining("no open promotion proposal");
    }

    @Test
    @Tag("EA-V3.7")
    void aDemotionIsImmediateAndShowsInTheNextCaseNotTheOneInProgress() {
        DecideHarness h = harness("drop to watch when 2 dangerous mistakes in 20 cases");
        h.levels.compareAndSet("Refund", "gold", null, new LevelState(Level.SUGGEST, 1, h.hashOfRefund(), false, DecideHarness.T0, "test", 1));
        // at suggest, every case is checked blind only when sampled: make the agent wrong in a dangerous way on every case, and check them all
        h.script = h.script.replace("trust {", "trust {\n check 100% of cases with a person who doesn't see the proposal");
        h.agent = f -> new String[] {"approve", "r", "0.9"};
        h.person = f -> "reject";

        Map<String, Object> first = h.runCase("run-1", h.inputs("gold", 20));
        assertThat(first.get("verdict_level")).isEqualTo("watch");
        assertThat(level(h, "gold")).as("one dangerous mistake").isEqualTo(Level.SUGGEST);
        h.runCase("run-2", h.inputs("gold", 21));
        assertThat(level(h, "gold")).as("the second").isEqualTo(Level.WATCH);
        assertThat(h.levels.get("Refund", "gold").orElseThrow().reason()).contains("2 dangerous mistakes in the last 2 cases");
        assertThat(h.audit).contains("level_changed");
        assertThat(h.ledger.records("Refund").stream().filter(r -> Rec.LEVEL.equals(r.kind()) && "demoted".equals(r.str("kind")))).hasSize(1);
    }

    @Test
    @Tag("EA-V3.5")
    void aConditionOnTheRememberedValuesSendsACaseToAPersonEvenAtAct() {
        DecideHarness h = harness("always ask a person when amount > 200");
        h.levels.compareAndSet("Refund", "gold", null, new LevelState(Level.ACT, 1, h.hashOfRefund(), false, DecideHarness.T0, "test", 1));

        Map<String, Object> small = h.runCase("run-1", h.inputs("gold", 150));
        Map<String, Object> large = h.runCase("run-2", h.inputs("gold", 250));

        assertThat(small.get("verdict_level")).isEqualTo("act");
        assertThat(large.get("verdict_level")).isEqualTo("suggest");
        assertThat(h.asked).hasSize(1);
        assertThat(h.ledger.get("Refund", "run-2/Triage/s0").orElseThrow().level()).isEqualTo(Level.SUGGEST);
    }

    @Test
    @Tag("EA-V3.5")
    void aDailyLimitSendsTheNextCaseAndTheCountSurvivesARestart() {
        DecideHarness h = harness("always ask a person after 3 cases a day");
        h.levels.compareAndSet("Refund", "gold", null, new LevelState(Level.ACT, 1, h.hashOfRefund(), false, DecideHarness.T0, "test", 1));
        for (int i = 0; i < 3; i++) assertThat(h.runCase("run-" + i, h.inputs("gold", 10 + i)).get("verdict_level")).isEqualTo("act");

        DecideHarness restarted = new DecideHarness(dir, h.ledger, h.levels, h.script); // a new process: nothing remembered but the ledger
        restarted.clock.set(h.clock.instant());
        assertThat(restarted.runCase("run-3", restarted.inputs("gold", 13)).get("verdict_level")).as("the fourth of the day").isEqualTo("suggest");
        restarted.day();
        assertThat(restarted.runCase("run-4", restarted.inputs("gold", 14)).get("verdict_level")).as("the next day").isEqualTo("act");
    }

    @Test
    @Tag("EA-V3.6")
    void auditSamplingSendsAShareOfCasesBlindAndTheSameCasesEveryTime() {
        int sampled = 0;
        for (int i = 0; i < 10_000; i++) if (io.github.llm4j.loom.execution.DeciderAccess.sampled("run-" + i + "/Triage/s0", "Refund", 5)) sampled++;
        assertThat(sampled).isBetween(450, 550);
        for (int i = 0; i < 2_000; i++) {
            String id = "run-" + i + "/Triage/s0";
            assertThat(io.github.llm4j.loom.execution.DeciderAccess.sampled(id, "Refund", 5)).as(id).isEqualTo(io.github.llm4j.loom.execution.DeciderAccess.sampled(id, "Refund", 5));
        }
        assertThat(io.github.llm4j.loom.execution.DeciderAccess.sampled("x", "Refund", 0)).isFalse();
        assertThat(io.github.llm4j.loom.execution.DeciderAccess.sampled("x", "Refund", 100)).isTrue();
    }

    @Test
    @Tag("EA-V3.6")
    void anAuditedCaseAtActIsAskedBlindAndCountsAsEvidenceExactlyAsInWatch() {
        DecideHarness h = harness("check 100% of cases with a person who doesn't see the proposal");
        h.levels.compareAndSet("Refund", "gold", null, new LevelState(Level.ACT, 1, h.hashOfRefund(), false, DecideHarness.T0, "test", 1));
        h.agent = f -> new String[] {"approve", "SECRET-REASON", "0.5"};

        Map<String, Object> vars = h.runCase("run-1", h.inputs("gold", 20));

        assertThat(vars.get("verdict_level")).isEqualTo("watch");
        assertThat(h.asked.get(0)).doesNotContain("SECRET-REASON").doesNotContain("proposes");
        assertThat(h.ledger.get("Refund", "run-1/Triage/s0").orElseThrow().blindEvidence()).isTrue();
    }

    @Test
    @Tag("EA-V3.8")
    void theDefaultCeilingIsSuggestSoReachingActIsAnSentenceTheScriptHadToWrite() {
        DecideHarness h = new DecideHarness(dir, new MemoryLedger(), new MemoryLevelStore(), Scripts2.refund("").replace("never go above act", ""));
        cases(h, "gold", 40, 0);
        assertThat(level(h, "gold")).isEqualTo(Level.SUGGEST);
    }

    @Test
    @Tag("EA-V3.10")
    void theLevelInForceIsJournaledSoARunResumedAfterAChangeFinishesTheCaseAtTheLevelItBeganWith() {
        DecideHarness h = harness("");
        RunJournal journal = RunJournal.inMemory();
        h.person = f -> {
            throw new RunSuspended("Triage/s0#decide-ask", "waiting for support-lead");
        };
        var first = h.newRun(journal);
        var executor = h.executor(first, "run-1");
        assertThatThrownBy(() -> executor.executeWorkflow("Triage", h.inputs("gold", 20))).isInstanceOf(RunSuspended.class);
        assertThat(journal.get("Triage/s0#level")).isPresent();

        // meanwhile the ladder moves: this scope is now at act
        LevelState now = h.levels.get("Refund", "gold").orElseThrow();
        h.levels.compareAndSet("Refund", "gold", now, now.withLevel(Level.ACT, true, DecideHarness.T0, "someone moved it"));
        journal.put("Triage/s0#decide-ask", new RunJournal.Entry("human", "approve"));

        h.asked.clear();
        var resumed = h.executor(h.newRun(journal), "run-1");
        resumed.executeWorkflow("Triage", h.inputs("gold", 20));
        assertThat(resumed.getContext().getAll().get("verdict_level")).as("the case began at watch").isEqualTo("watch");
        assertThat(resumed.getContext().getAll().get("verdict")).isEqualTo("approve");
        assertThat(h.ledger.cases("Refund")).hasSize(1);

        Map<String, Object> next = h.runCase("run-2", h.inputs("gold", 21));
        assertThat(next.get("verdict_level")).as("the next case uses the new level").isEqualTo("act");
    }
}
