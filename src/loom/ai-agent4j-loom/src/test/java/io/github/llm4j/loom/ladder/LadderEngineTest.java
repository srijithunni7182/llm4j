package io.github.llm4j.loom.ladder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.ast.RungDef;
import io.github.llm4j.loom.autonomy.Rec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Where a scope stands on its ladder, and every way that changes. */
@Tag("L4")
class LadderEngineTest {

    static String script(String climbing, String ceiling, String onChange) {
        return """
                agent T { model: "m" system: "s" output_schema: { choice: string, reasoning: string } }
                measure agrees(c) = c.response.choice == c.proposal.choice
                ladder L {
                    rung watch { mode: blind }
                    rung suggest {
                        mode: confirm
                        climb when history.count >= 5 and history.rate(agrees) >= 0.8
                        slip when history.last(5).rate(agrees) < 0.4
                    }
                    rung act {
                        mode: autonomous
                        climb when history.count >= 10 and history.rate(agrees) >= 0.9 and history.count(dangerous) == 0
                        slip to suggest when history.last(10).count(dangerous) >= 2
                        sample 20% blind
                        escalate when case.amount > 200
                    }
                    @CLIMBING@
                }
                decision D {
                    proposed by: T
                    choices: approve, reject, escalate
                    remember: amount
                    ask: lead
                    dangerous mistake: propose approve, person decides reject
                    ladder: L
                    ceiling: @CEILING@
                    when the agent changes: @ONCHANGE@
                }
                """.replace("@CLIMBING@", climbing).replace("@CEILING@", ceiling).replace("@ONCHANGE@", onChange);
    }

    static final String APPROVAL = script("climbing needs approval from risk-owner", "act", "start over");
    static final String AUTO = script("climbing is automatic", "act", "start over");

    @Test
    void aNewScopeStartsOnTheFirstRungAndTheFirstStandingIsRecordedOnce() {
        LadderHarness h = new LadderHarness(APPROVAL);
        LevelLog.Standing s = h.engine.standing("gold", "id-a", "m-1");
        assertThat(s.rungName()).isEqualTo("watch");
        assertThat(s.version()).isEqualTo(1);
        assertThat(s.epoch()).isEqualTo(1);
        assertThat(s.rules()).isEqualTo(h.policy.fingerprint());
        assertThat(h.engine.standing("gold", "id-a", "m-1")).isEqualTo(s);
        assertThat(h.ledger.recordsOfKind("D", Rec.LEVEL)).hasSize(1);
        assertThat(h.engine.standing("basic", "id-a", "m-1").version()).isEqualTo(1);
        assertThat(h.ledger.recordsOfKind("D", Rec.LEVEL)).hasSize(2);
    }

    @Test
    void anEarnedClimbIsProposedOnceAndWaitsForTheNamedPerson() {
        LadderHarness h = new LadderHarness(APPROVAL);
        h.engine.standing("all", "id-a", "m-1");
        h.answeredMany(4, "all", "approve", "approve");
        assertThat(h.engine.afterResponse("all")).isEmpty();
        h.answered("all", "approve", "approve");
        List<LadderEngine.Change> changes = h.engine.afterResponse("all");
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).kind()).isEqualTo("proposed");
        assertThat(changes.get(0).to()).isEqualTo("suggest");
        assertThat(changes.get(0).reason()).contains("history.count >= 5").contains("waiting for risk-owner");
        assertThat(h.engine.afterResponse("all")).isEmpty();                     // not proposed again
        assertThat(h.ledger.recordsOfKind("D", Rec.PROMOTION_PROPOSED)).hasSize(1);
        assertThat(h.engine.standing("all", "id-a", "m-1").rungName()).isEqualTo("watch");
        assertThat(h.engine.openProposal("all")).isPresent();

        assertThatThrownBy(() -> h.engine.approve("all", "someone-else", "ok")).hasMessageContaining("only risk-owner may approve");
        assertThatThrownBy(() -> h.engine.approve("all", "agent", "ok")).hasMessageContaining("only risk-owner");
        LadderEngine.Change c = h.engine.approve("all", "risk-owner", "numbers look right");
        assertThat(c.kind()).isEqualTo("promoted");
        LevelLog.Standing now = h.engine.standing("all", "id-a", "m-1");
        assertThat(now.rungName()).isEqualTo("suggest");
        assertThat(now.version()).isEqualTo(2);
        assertThat(now.reason()).contains("approved by risk-owner");
        assertThat(h.engine.openProposal("all")).isEmpty();
        assertThatThrownBy(() -> h.engine.approve("all", "risk-owner", "again")).hasMessageContaining("no open promotion");
    }

    @Test
    void anAutomaticLadderClimbsAtOnceAndSaysWhy() {
        LadderHarness h = new LadderHarness(AUTO);
        h.engine.standing("all", "id-a", "m-1");
        h.answeredMany(5, "all", "approve", "approve");
        List<LadderEngine.Change> changes = h.engine.afterResponse("all");
        assertThat(changes).extracting(LadderEngine.Change::kind).containsExactly("promoted");
        assertThat(h.engine.standing("all", "id-a", "m-1").rungName()).isEqualTo("suggest");
        assertThat(h.engine.standing("all", "id-a", "m-1").reason()).contains("history.count >= 5 [5 vs 5]");
    }

    @Test
    void aClimbThatTheNextRungWouldUndoAtOnceIsNotMade() {
        LadderHarness h = new LadderHarness(AUTO);
        h.engine.standing("all", "id-a", "m-1");
        h.answeredMany(2, "all", "approve", "reject");           // last 5 agree under 40%...
        h.answeredMany(3, "all", "approve", "approve");          // ...but overall 3 of 5 = 60% does not climb anyway
        assertThat(h.engine.afterResponse("all")).isEmpty();
    }

    @Test
    void theCeilingStopsAClimbThatIsEarned() {
        LadderHarness h = new LadderHarness(script("climbing is automatic", "suggest", "start over"));
        h.engine.standing("all", "id-a", "m-1");
        h.answeredMany(5, "all", "approve", "approve");
        h.engine.afterResponse("all");
        assertThat(h.engine.standing("all", "id-a", "m-1").rungName()).isEqualTo("suggest");
        h.answeredMany(20, "all", "approve", "approve");
        assertThat(h.engine.afterResponse("all")).isEmpty();
        assertThat(h.engine.standing("all", "id-a", "m-1").rungName()).isEqualTo("suggest");
    }

    @Test
    void aSlipIsAppliedAtOnceToTheRungBelowOrTheOneNamed() {
        LadderHarness h = new LadderHarness(AUTO);
        h.engine.standing("all", "id-a", "m-1");
        h.answeredMany(5, "all", "approve", "approve");
        h.engine.afterResponse("all");                                  // suggest
        h.answeredMany(5, "all", "approve", "approve");
        h.engine.afterResponse("all");                                  // 10 of 10: act
        assertThat(h.engine.standing("all", "id-a", "m-1").rungName()).isEqualTo("act");
        h.answered("all", "approve", "reject", "act", "id-a", 1, Map.of());
        assertThat(h.engine.afterResponse("all")).isEmpty();            // one dangerous mistake is not enough
        h.answered("all", "approve", "reject", "act", "id-a", 1, Map.of());
        List<LadderEngine.Change> changes = h.engine.afterResponse("all");
        assertThat(changes).extracting(LadderEngine.Change::kind).containsExactly("demoted");
        assertThat(changes.get(0).from()).isEqualTo("act");
        assertThat(changes.get(0).to()).isEqualTo("suggest");
        assertThat(changes.get(0).reason()).contains("history.last(10).count(dangerous) >= 2 [2 vs 2]");
        assertThat(h.engine.standing("all", "id-a", "m-1").rungName()).isEqualTo("suggest");
    }

    @Test
    void aLateOutcomeCanCauseASlipAndIsRecordedWithItsValue() {
        String s = script("climbing is automatic", "act", "start over").replace("history.last(10).count(dangerous) >= 2", "history.count(reverted) >= 1")
                .replace("measure agrees", "measure reverted(c) = c.outcome == \"reverted\"\nmeasure agrees");
        LadderHarness h = new LadderHarness(s);
        h.engine.standing("all", "id-a", "m-1");
        h.answeredMany(5, "all", "approve", "approve");
        h.engine.afterResponse("all");
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) ids.add(h.answered("all", "approve", "approve"));
        h.engine.afterResponse("all");
        assertThat(h.engine.standing("all", "id-a", "m-1").rungName()).isEqualTo("act");
        List<LadderEngine.Change> none = h.engine.outcome(ids.get(0), "views_7d", 5400, null);
        assertThat(none).isEmpty();
        List<LadderEngine.Change> changes = h.engine.outcome(ids.get(1), "reverted", null, "chargeback");
        assertThat(changes).extracting(LadderEngine.Change::kind).containsExactly("demoted");
        assertThat(h.ledger.recordsOfKind("D", Rec.OUTCOME)).hasSize(2);
        assertThatThrownBy(() -> h.engine.outcome("no-such-case", "x", null, null)).hasMessageContaining("no case no-such-case");
    }

    @Test
    void aFreezeStopsAutonomyForNewCasesAndAnUnfreezeRestoresIt() {
        LadderHarness h = new LadderHarness(AUTO);
        h.engine.standing("all", "id-a", "m-1");
        h.answeredMany(5, "all", "approve", "approve");
        h.engine.afterResponse("all");
        h.answeredMany(5, "all", "approve", "approve");
        h.engine.afterResponse("all");
        LevelLog.Standing s = h.engine.standing("all", "id-a", "m-1");
        assertThat(h.engine.effective(s)).isEqualTo(2);
        h.engine.freeze("incident 42", "ops");
        assertThat(h.engine.frozen()).isTrue();
        assertThat(h.engine.effective(s)).isEqualTo(1);                 // held below the first autonomous rung
        h.engine.unfreeze("resolved", "ops");
        assertThat(h.engine.frozen()).isFalse();
        assertThat(h.engine.effective(s)).isEqualTo(2);
    }

    @Test
    void theCeilingHoldsAnEarnedRungDownWithoutLosingIt() {
        LadderHarness h = new LadderHarness(AUTO);
        h.engine.standing("all", "id-a", "m-1");
        h.answeredMany(5, "all", "approve", "approve");
        h.engine.afterResponse("all");
        LevelLog.Standing s = h.engine.standing("all", "id-a", "m-1");
        LadderEngine lower = new LadderEngine(new LadderHarness(script("climbing is automatic", "watch", "start over")).policy, h.ledger, h.clock);
        assertThat(h.engine.effective(s)).isEqualTo(1);
        assertThat(lower.effective(s)).isEqualTo(0);
    }

    // ---------- planning a case ----------

    @Test
    void theModeOfACaseComesFromItsRungWithSamplingAndEscalationOnTop() {
        LadderHarness h = new LadderHarness(AUTO);
        h.engine.standing("all", "id-a", "m-1");
        h.answeredMany(5, "all", "approve", "approve");
        h.engine.afterResponse("all");
        h.answeredMany(5, "all", "approve", "approve");
        h.engine.afterResponse("all");
        LevelLog.Standing s = h.engine.standing("all", "id-a", "m-1");
        int blind = 0, confirm = 0, autonomous = 0;
        for (int i = 0; i < 2000; i++) {
            LadderEngine.Plan p = h.engine.plan("all", "run-" + i, s, HistoryBuilder.record(caseWithAmount("run-" + i, 10)), 
                    HistoryBuilder.history(LedgerFold.cases(h.ledger.records("D")), "all", 1), h.clock.millis());
            assertThat(p.rung()).isEqualTo("act");
            switch (p.mode()) {
                case BLIND -> { blind++; assertThat(p.how()).isEqualTo("sample"); }
                case AUTONOMOUS -> autonomous++;
                default -> confirm++;
            }
        }
        assertThat(blind / 2000.0).isBetween(0.16, 0.24);
        assertThat(confirm).isZero();
        assertThat(autonomous + blind).isEqualTo(2000);
        LadderEngine.Plan big = h.engine.plan("all", "run-x", s, HistoryBuilder.record(caseWithAmount("run-x", 500)),
                HistoryBuilder.history(LedgerFold.cases(h.ledger.records("D")), "all", 1), h.clock.millis());
        assertThat(big.mode()).isEqualTo(RungDef.Mode.CONFIRM);
        assertThat(big.how()).isEqualTo("escalate");
        h.engine.freeze("incident", "ops");
        LadderEngine.Plan frozen = h.engine.plan("all", "run-y", s, HistoryBuilder.record(caseWithAmount("run-y", 10)),
                HistoryBuilder.history(LedgerFold.cases(h.ledger.records("D")), "all", 1), h.clock.millis());
        assertThat(frozen.mode()).isEqualTo(RungDef.Mode.CONFIRM);
        assertThat(frozen.rung()).isEqualTo("suggest");
        assertThat(frozen.how()).isEqualTo("frozen");
    }

    private static io.github.llm4j.loom.ladder.LCase caseWithAmount(String id, int amount) {
        return new LCase(id, "D", "all", LadderHarness.START, "act", "act", "id-a", 1, 1, false, Map.of("amount", String.valueOf(amount)), "m-1",
                null, null, null, null, false, null, List.of(), true, "accept", "approve", null, "agent", false, 0L, LadderHarness.START, List.of());
    }

    // ---------- change ----------

    @Test
    void aNewAgentStartsANewEpochAccordingToWhatTheDecisionSays() {
        LadderHarness h = new LadderHarness(AUTO);
        h.engine.standing("all", "id-a", "m-1");
        h.answeredMany(5, "all", "approve", "approve");
        h.engine.afterResponse("all");
        assertThat(h.engine.standing("all", "id-a", "m-1").rungName()).isEqualTo("suggest");

        LevelLog.Standing next = h.engine.standing("all", "id-b", "m-1");
        assertThat(next.epoch()).isEqualTo(2);
        assertThat(next.rungName()).isEqualTo("watch");                    // start over
        assertThat(next.reason()).contains("the agent behind D changed");
        assertThat(h.engine.history("all", 2).cases()).isEmpty();            // the old epoch's cases do not count...
        assertThat(h.engine.historyAll("all").cases()).hasSize(5);           // ...but they are still there

        LadderHarness keep = new LadderHarness(script("climbing is automatic", "suggest", "keep the trust"));
        keep.engine.standing("all", "id-a", "m-1");
        keep.answeredMany(5, "all", "approve", "approve");
        keep.engine.afterResponse("all");
        LevelLog.Standing kept = keep.engine.standing("all", "id-b", "m-1");
        assertThat(kept.epoch()).isEqualTo(2);
        assertThat(kept.rungName()).isEqualTo("suggest");
    }

    @Test
    void aChangeOfServedModelAloneStartsANewEpochAndBothIdsAreRecorded() {
        LadderHarness h = new LadderHarness(AUTO);
        h.engine.standing("all", "id-a", "m-1");
        LevelLog.Standing same = h.engine.standing("all", "id-a", "unreported");
        assertThat(same.epoch()).isEqualTo(1);                              // a provider that does not report never opens an epoch
        LevelLog.Standing changed = h.engine.standing("all", "id-a", "m-2");
        assertThat(changed.epoch()).isEqualTo(2);
        assertThat(changed.reason()).contains("served model").contains("m-1").contains("m-2");
        assertThat(h.engine.standing("all", "id-a", "m-2")).isEqualTo(changed);
    }

    @Test
    void aHandSetLevelNeedsForceToGoAboveTheCeilingOrTheEvidenceAndNeverToComeDown() {
        LadderHarness h = new LadderHarness(script("climbing is automatic", "suggest", "start over"));
        h.engine.standing("all", "id-a", "m-1");
        assertThatThrownBy(() -> h.engine.set("all", "suggest", false, "because", "ops")).hasMessageContaining("not supported by the evidence").hasMessageContaining("--force");
        assertThatThrownBy(() -> h.engine.set("all", "act", false, "because", "ops")).hasMessageContaining("above the ceiling");
        LadderEngine.Change up = h.engine.set("all", "suggest", true, "incident", "ops");
        assertThat(up.forced()).isTrue();
        assertThat(h.engine.standing("all", "id-a", "m-1").forced()).isTrue();
        LadderEngine.Change down = h.engine.set("all", "watch", false, "model changed", "ops");
        assertThat(down.to()).isEqualTo("watch");
        assertThat(h.engine.standing("all", "id-a", "m-1").forced()).isFalse();
        assertThatThrownBy(() -> h.engine.set("all", "nowhere", false, "x", "ops")).hasMessageContaining("no rung named nowhere");
        assertThatThrownBy(() -> h.engine.set("nobody", "watch", false, "x", "ops")).hasMessageContaining("no such scope");
    }

    @Test
    void aRejectedProposalIsNotAskedAgainUntilTheEvidenceHasGrown() {
        LadderHarness h = new LadderHarness(APPROVAL);
        h.engine.standing("all", "id-a", "m-1");
        h.answeredMany(10, "all", "approve", "approve");
        assertThat(h.engine.afterResponse("all")).hasSize(1);
        assertThat(h.engine.reject("all", "risk-owner", "not yet").kind()).isEqualTo("rejected");
        assertThat(h.engine.openProposal("all")).isEmpty();
        h.answeredMany(1, "all", "approve", "approve");
        assertThat(h.engine.afterResponse("all")).isEmpty();
        h.answeredMany(3, "all", "approve", "approve");                    // 14 cases against 10: the evidence has grown by more than a fifth
        assertThat(h.engine.afterResponse("all")).extracting(LadderEngine.Change::kind).containsExactly("proposed");
    }

    // ---------- change of rules ----------

    @Test
    void aTightenedLadderDemotesAtOnceALoosenedOneOnlyMakesAClimbPossible() {
        LadderHarness h = new LadderHarness(AUTO);
        h.engine.standing("all", "id-a", "m-1");
        h.answeredMany(5, "all", "approve", "approve");
        h.engine.afterResponse("all");
        h.answeredMany(5, "all", "approve", "approve");
        h.engine.afterResponse("all");
        assertThat(h.engine.standing("all", "id-a", "m-1").rungName()).isEqualTo("act");

        LadderEngine unchanged = h.restart(AUTO);
        assertThat(unchanged.reevaluate()).isEmpty();                        // same rules: nothing happens
        LadderEngine tighter = h.restart(AUTO.replace("history.count >= 10 and", "history.count >= 50 and"));
        List<LadderEngine.Change> changes = tighter.reevaluate();
        assertThat(changes).extracting(LadderEngine.Change::kind).containsExactly("demoted");
        assertThat(changes.get(0).reason()).contains("rules changed").contains("history.count >= 50");
        assertThat(tighter.standing("all", "id-a", "m-1").rungName()).isEqualTo("suggest");
        assertThat(tighter.reevaluate()).isEmpty();                          // idempotent

        LadderEngine looser = h.restart(AUTO.replace("history.count >= 10 and", "history.count >= 3 and").replace("history.count >= 5 and", "history.count >= 2 and"));
        assertThat(looser.reevaluate()).isEmpty();                           // never promoted by the change itself
        assertThat(looser.standing("all", "id-a", "m-1").rungName()).isEqualTo("suggest");
    }

    @Test
    void aLoweredCeilingDemotesWithThatReason() {
        LadderHarness h = new LadderHarness(AUTO);
        h.engine.standing("all", "id-a", "m-1");
        h.answeredMany(5, "all", "approve", "approve");
        h.engine.afterResponse("all");
        LadderEngine lower = h.restart(script("climbing is automatic", "watch", "start over"));
        List<LadderEngine.Change> changes = lower.reevaluate();
        assertThat(changes).extracting(LadderEngine.Change::kind).containsExactly("demoted");
        assertThat(changes.get(0).reason()).contains("ceiling");
    }

    // ---------- concurrency ----------

    @Test
    void twoWritersNeverMakeTwoLevelRecordsForTheSameStep() throws Exception {
        LadderHarness h = new LadderHarness(AUTO);
        h.engine.standing("all", "id-a", "m-1");
        h.answeredMany(5, "all", "approve", "approve");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<java.util.concurrent.Future<List<LadderEngine.Change>>> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return h.engine.afterResponse("all");
            }));
        }
        go.countDown();
        int made = 0;
        for (var f : results) made += f.get().size();
        pool.shutdown();
        assertThat(made).isEqualTo(1);
        assertThat(h.engine.standing("all", "id-a", "m-1").version()).isEqualTo(2);
        assertThat(h.ledger.recordsOfKind("D", Rec.LEVEL).stream().map(Rec::id).distinct().count()).isEqualTo(2);
    }

    @Test
    void noDataMovesNothing() {
        LadderHarness h = new LadderHarness(AUTO);
        h.engine.standing("all", "id-a", "m-1");
        assertThat(h.engine.afterResponse("all")).isEmpty();
        assertThat(h.engine.afterResponse("unknown-scope")).isEmpty();
        h.clock.advance(Duration.ofDays(400));
        assertThat(h.engine.afterResponse("all")).isEmpty();
    }
}
