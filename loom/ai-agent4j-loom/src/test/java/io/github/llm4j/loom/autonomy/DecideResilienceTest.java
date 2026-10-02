package io.github.llm4j.loom.autonomy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.runtime.RunJournal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Crashes, a broken ledger, bad proposals, loops and rewinds (spec loom-earned-autonomy R1.5, R2, R8). */
class DecideResilienceTest {

    @TempDir
    Path dir;

    private DecideHarness harness() {
        return new DecideHarness(dir, new MemoryLedger(), new MemoryLevelStore(), Scripts2.refund(""));
    }

    @Test
    @Tag("EA-V2.3")
    void aRunThatCrashesAtEveryJournalWriteAndIsResumedEndsWithExactlyOneCaseOneProposalAndOneVerdict() {
        // how many writes a clean case makes
        DecideHarness probe = harness();
        FaultJournal counter = new FaultJournal(RunJournal.inMemory(), 0, false);
        probe.executor(probe.newRun(counter), "run-1").executeWorkflow("Triage", probe.inputs("gold", 20));
        int writes = counter.puts();
        assertThat(writes).isGreaterThan(5);

        for (int crashAt = 1; crashAt <= writes; crashAt++) {
            for (boolean after : new boolean[] {false, true}) {
                DecideHarness h = harness();
                RunJournal durable = RunJournal.inMemory();
                try {
                    h.executor(h.newRun(new FaultJournal(durable, crashAt, after)), "run-1").executeWorkflow("Triage", h.inputs("gold", 20));
                } catch (FaultJournal.Crash expected) {
                    // the process died here
                }
                var resumed = h.executor(h.newRun(durable), "run-1");
                resumed.executeWorkflow("Triage", h.inputs("gold", 20));

                String at = "crash " + (after ? "after" : "before") + " write " + crashAt;
                List<Case> cases = h.ledger.cases("Refund");
                assertThat(cases).as(at).hasSize(1);
                assertThat(cases.get(0).verdict()).as(at).isEqualTo("approve");
                assertThat(cases.get(0).proposal()).as(at).isEqualTo("approve");
                assertThat(h.ledger.records("Refund").stream().filter(r -> Rec.DECIDED.equals(r.kind()))).as(at).hasSize(1);
                assertThat(resumed.getContext().getAll().get("verdict")).as(at).isEqualTo("approve");
            }
        }
    }

    @Test
    @Tag("EA-V2.3")
    void aVerdictInTheJournalIsAppendedToALedgerThatWasDownWhenItWasGiven() {
        DecideHarness h = new DecideHarness(dir, new FaultLedger(new MemoryLedger()), new MemoryLevelStore(), Scripts2.refund(""));
        FaultLedger ledger = (FaultLedger) h.ledger;
        ledger.failWrites = true;
        RunJournal journal = RunJournal.inMemory();
        var first = h.executor(h.newRun(journal), "run-1");
        first.executeWorkflow("Triage", h.inputs("gold", 20));
        assertThat(first.getContext().getAll().get("verdict")).as("the run is not stopped by the ledger").isEqualTo("approve");
        assertThat(h.audit).doesNotContain("x");
        assertThat(h.last.audit).contains("autonomy_degraded");
        assertThat(ledger.records("Refund")).isEmpty();

        ledger.failWrites = false;
        h.executor(h.newRun(journal), "run-1").executeWorkflow("Triage", h.inputs("gold", 20));
        assertThat(ledger.cases("Refund")).hasSize(1);
        assertThat(ledger.cases("Refund").get(0).verdict()).isEqualTo("approve");
    }

    @Test
    @Tag("EA-V8.4")
    void ifTheLedgerOrTheLevelStoreCannotBeReadTheDecisionRunsAtWatchAndSaysWhy() {
        for (String broken : List.of("ledger", "levels", "both")) {
            FaultLedger ledger = new FaultLedger(new MemoryLedger());
            FaultLevels levels = new FaultLevels(new MemoryLevelStore());
            DecideHarness h = new DecideHarness(dir, ledger, levels, Scripts2.refund(""));
            // the ladder says act, but the stores cannot be trusted right now
            levels.compareAndSet("Refund", "gold", null, new LevelState(Level.ACT, 1, h.hashOfRefund(), false, DecideHarness.T0, "earned", 1));
            levels.failReads = broken.equals("levels") || broken.equals("both");
            ledger.failReads = broken.equals("ledger") || broken.equals("both");
            ledger.failWrites = broken.equals("ledger") || broken.equals("both");
            if (broken.equals("ledger")) h.script = h.script.replace("never go above act", "never go above act\n always ask a person after 5 cases a day"); // reads the ledger at act

            Map<String, Object> vars = h.runCase("run-1", h.inputs("gold", 20));

            assertThat(vars.get("verdict_level")).as(broken).isEqualTo("watch");
            assertThat(h.asked).as(broken).hasSize(1);
            assertThat(h.audit).as(broken).contains("autonomy_degraded");
        }
    }

    @Test
    @Tag("EA-V8.4")
    void anUnreachableDeciderPausesTheRunAndNeverFallsBackToAHigherLevel() {
        DecideHarness h = harness();
        h.person = f -> {
            throw new io.github.llm4j.loom.runtime.RunSuspended("Triage/s0#decide-ask", "waiting");
        };
        assertThatThrownBy(() -> h.runCase("run-1", h.inputs("gold", 20))).isInstanceOf(io.github.llm4j.loom.runtime.RunSuspended.class);
        assertThat(h.levels.get("Refund", "gold").orElseThrow().level()).isEqualTo(Level.WATCH);
    }

    @Test
    @Tag("EA-V8.2")
    void anInvalidChoiceIsRetriedOnceThenRecordedAsAMalformedEscalation() {
        DecideHarness h = harness();
        h.agent = f -> new String[] {"refund-everything", "sure", "0.9"};
        h.person = f -> "reject";

        Map<String, Object> vars = h.runCase("run-1", h.inputs("gold", 20));

        assertThat(h.modelCalls).as("one retry").isEqualTo(2);
        assertThat(vars.get("verdict")).isEqualTo("reject");
        assertThat(vars.get("verdict_proposal")).isEqualTo("escalate");
        Case c = h.ledger.get("Refund", "run-1/Triage/s0").orElseThrow();
        assertThat(c.malformed()).isTrue();
        assertThat(c.proposal()).isEqualTo("escalate");
        assertThat(h.tasks.get(1)).contains("did not name one of the choices");
    }

    @Test
    @Tag("EA-V8.2")
    void aRetryThatFixesTheChoiceIsNotMalformedAndAProposalThatFailsIsSentToAPersonAsUnusable() {
        DecideHarness h = harness();
        int[] calls = {0};
        h.agent = f -> new String[] {calls[0]++ == 0 ? "maybe" : "reject", "second try", "0.5"};
        Map<String, Object> vars = h.runCase("run-1", h.inputs("gold", 20));
        assertThat(vars.get("verdict_proposal")).isEqualTo("reject");
        assertThat(h.ledger.get("Refund", "run-1/Triage/s0").orElseThrow().malformed()).isFalse();

        DecideHarness broken = harness();
        broken.agent = f -> {
            throw new IllegalStateException("the model is down");
        };
        Map<String, Object> failed = broken.runCase("run-2", broken.inputs("gold", 20));
        assertThat(failed.get("verdict_proposal")).isEqualTo("escalate");
        assertThat(broken.ledger.get("Refund", "run-2/Triage/s0").orElseThrow().malformed()).isTrue();
        assertThat(broken.asked).hasSize(1);
    }

    @Test
    @Tag("EA-V8.2")
    void anAnswerThatIsNotAChoiceIsAskedAgainOnceThenFailsTheStep() {
        DecideHarness h = harness();
        h.person = f -> "banana";
        assertThatThrownBy(() -> h.runCase("run-1", h.inputs("gold", 20))).hasMessageContaining("not one of");
        assertThat(h.asked).hasSize(2);
        assertThat(h.asked.get(1)).contains("Please answer with exactly one of");

        DecideHarness prefix = harness();
        prefix.person = f -> "REJ";
        assertThat(prefix.runCase("run-2", prefix.inputs("gold", 20)).get("verdict")).as("a unique prefix, any case").isEqualTo("reject");
        DecideHarness ambiguous = harness();
        ambiguous.person = f -> "e";
        assertThat(ambiguous.runCase("run-3", ambiguous.inputs("gold", 20)).get("verdict")).isEqualTo("escalate");
    }

    @Test
    @Tag("EA-V1.4")
    void eachIterationOfALoopIsItsOwnCaseWithAStableId() {
        String script = Scripts2.refund("").replace("workflow Triage(ticket, tier, amount, reason, customer_since) {\n    decide Refund -> verdict",
                "workflow Triage(ticket, tier, amount, reason, customer_since) {\n    loop until (verdict == \"never\") max 3 {\n    decide Refund -> verdict\n    }");
        DecideHarness h = new DecideHarness(dir, new MemoryLedger(), new MemoryLevelStore(), script);
        Map<String, String> in = h.inputs("gold", 20);
        RunJournal journal = RunJournal.inMemory();

        h.executor(h.newRun(journal), "run-1").executeWorkflow("Triage", in);
        List<String> ids = h.ledger.cases("Refund").stream().map(Case::id).toList();
        assertThat(ids).hasSize(3).doesNotHaveDuplicates();

        h.executor(h.newRun(journal), "run-1").executeWorkflow("Triage", in); // a resume
        assertThat(h.ledger.cases("Refund").stream().map(Case::id).toList()).as("a resume adds nothing").isEqualTo(ids);
    }

    @Test
    @Tag("EA-V2.9")
    void aCaseThatIsRewoundAndDecidedAgainCountsOnceAndThePersonIsNotAskedTwice() {
        String script = Scripts2.refund("").replace("    decide Refund -> verdict\n",
                "    checkpoint before\n    decide Refund -> verdict\n    rewind to before when (verdict == \"reject\") at most 1 time if it still fails { note \"gave up\" }\n");
        DecideHarness h = new DecideHarness(dir, new MemoryLedger(), new MemoryLevelStore(), script);
        h.person = f -> "reject"; // the person always rejects, so the workflow goes back once and decides again

        h.runCase("run-1", h.inputs("gold", 20));

        List<Case> all = h.ledger.cases("Refund");
        assertThat(all).hasSize(2);
        assertThat(all.get(0).superseded()).isTrue();
        assertThat(all.get(1).superseded()).isFalse();
        assertThat(all.get(1).generation()).isEqualTo(2);
        assertThat(h.asked).as("the identical question is not asked again").hasSize(1);
        assertThat(Cases.current(all)).hasSize(1);
        assertThat(new Ladder(DecisionParseTest.parse(script).getDecisions().get(0), h.ledger, h.clock).evidence("gold", 1)).as("counts once").hasSize(1);
    }
}
