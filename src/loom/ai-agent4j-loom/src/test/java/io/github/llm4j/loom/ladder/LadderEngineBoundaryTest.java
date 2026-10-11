package io.github.llm4j.loom.ladder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.ast.RungDef;
import io.github.llm4j.loom.autonomy.Rec;
import io.github.llm4j.loom.autonomy.expr.V;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The edges of the engine: exact thresholds, the wording of what it reports, and the paths a mutation test showed nothing walked. */
@Tag("L4")
class LadderEngineBoundaryTest {

    static final String AUTO = LadderEngineTest.AUTO;
    static final String APPROVAL = LadderEngineTest.APPROVAL;

    // ---------- what is said ----------

    @Test
    void changesAreReportedInWordsThatNameTheScopeTheMoveAndTheReason() {
        var promoted = new LadderEngine.Change("promoted", "gold", "watch", "suggest", "climb: ok", "runtime", false);
        assertThat(promoted.sentence()).isEqualTo("scope gold moved from watch to suggest (promoted): climb: ok");
        assertThat(new LadderEngine.Change("set", "all", "act", "watch", "drill", "ops", true).sentence()).isEqualTo("the decision moved from act to watch (set, forced): drill");
        assertThat(new LadderEngine.Change("set", "", "act", "watch", "drill", "ops", false).sentence()).startsWith("the decision moved");
        assertThat(new LadderEngine.Change("proposed", "gold", "watch", "suggest", "why", "runtime", false).sentence()).isEqualTo("promotion of scope gold to suggest proposed: why");
        assertThat(new LadderEngine.Change("rejected", "all", "watch", "suggest", "no", "boss", false).sentence()).isEqualTo("promotion of the decision to suggest rejected: no");
    }

    @Test
    void refusalsNameTheScopeOrTheDecision() {
        LadderHarness h = new LadderHarness(APPROVAL);
        h.engine.standing("gold", "id-a", "m-1");
        assertThatThrownBy(() -> h.engine.approve("gold", "risk-owner", "x")).hasMessage("there is no open promotion proposal for scope gold");
        assertThatThrownBy(() -> h.engine.reject("all", "risk-owner", "x")).hasMessage("there is no open promotion proposal for this decision");
        assertThatThrownBy(() -> h.engine.approve("nobody", "risk-owner", "x")).isInstanceOf(IllegalStateException.class);
    }

    // ---------- approving and rejecting ----------

    @Test
    void onlyTheApproverMayRejectAndARejectionIsRecordedWithItsCases() {
        LadderHarness h = new LadderHarness(APPROVAL);
        h.engine.standing("all", "id-a", "m-1");
        h.answeredMany(5, "all", "approve", "approve");
        h.engine.afterResponse("all");
        assertThatThrownBy(() -> h.engine.reject("all", "intruder", "no")).hasMessage("only risk-owner may decide on moving up (you said intruder)");
        assertThat(h.engine.openProposal("all")).isPresent();
        LadderEngine.Change c = h.engine.reject("all", "risk-owner", "not yet");
        assertThat(c.kind()).isEqualTo("rejected");
        assertThat(c.from()).isEqualTo("watch");
        assertThat(c.to()).isEqualTo("suggest");
        assertThat(h.ledger.recordsOfKind("D", Rec.PROMOTION_DECIDED).get(0).num("cases")).isEqualTo(5);
    }

    @Test
    void aRejectedProposalIsAskedAgainExactlyWhenTheEvidenceHasGrownByAFifth() {
        LadderHarness h = new LadderHarness(APPROVAL.replace("history.count >= 5 and", "history.count >= 1 and"));
        h.engine.standing("all", "id-a", "m-1");
        h.answeredMany(10, "all", "approve", "approve");
        h.engine.afterResponse("all");
        h.engine.reject("all", "risk-owner", "no");
        h.answeredMany(1, "all", "approve", "approve");                  // 11: under 12
        assertThat(h.engine.afterResponse("all")).isEmpty();
        h.answeredMany(1, "all", "approve", "approve");                  // 12: exactly a fifth more
        assertThat(h.engine.afterResponse("all")).extracting(LadderEngine.Change::kind).containsExactly("proposed");
    }

    @Test
    void aProposalNoLongerOpenOnceTheScopeHasPassedItOrMovedToANewEpoch() {
        LadderHarness h = new LadderHarness(APPROVAL);
        h.engine.standing("all", "id-a", "m-1");
        h.answeredMany(5, "all", "approve", "approve");
        h.engine.afterResponse("all");
        assertThat(h.engine.openProposal("all")).isPresent();
        h.engine.set("all", "suggest", true, "hand", "ops");
        assertThat(h.engine.openProposal("all")).as("the scope is already there").isEmpty();
        h.engine.set("all", "watch", false, "back", "ops");
        assertThat(h.engine.openProposal("all")).as("proposed at the same standing, so open again").isPresent();
        h.engine.standing("all", "id-b", "m-1");
        assertThat(h.engine.openProposal("all")).as("a new epoch's evidence is not the old one's").isEmpty();
        assertThat(h.engine.openProposal("unknown")).isEmpty();
    }

    // ---------- climbing and slipping ----------

    @Test
    void aClimbIsAllowedAtTheCeilingAndRefusedPastIt() {
        LadderHarness h = new LadderHarness(LadderEngineTest.script("climbing is automatic", "suggest", "start over"));
        h.engine.standing("all", "id-a", "m-1");
        h.answeredMany(5, "all", "approve", "approve");
        assertThat(h.engine.afterResponse("all")).extracting(LadderEngine.Change::kind).containsExactly("promoted");      // suggest == the ceiling
        assertThat(h.engine.standing("all", "id-a", "m-1").rungName()).isEqualTo("suggest");
    }

    @Test
    void theSlipConditionOfARungIsReadAtOnceAndTheFlapCheckStopsAClimbItWouldUndo() {
        // suggest slips when the last five agree under 40%; act's own slip needs two dangerous mistakes
        LadderHarness h = new LadderHarness(AUTO);
        h.engine.standing("all", "id-a", "m-1");
        for (int i = 0; i < 6; i++) h.answered("all", "approve", "approve");
        h.answered("all", "approve", "reject");
        h.answered("all", "approve", "reject");
        h.answered("all", "approve", "reject");
        h.answered("all", "approve", "reject");
        // 6 of 10 overall = 60%: not enough to climb, and the last five (1 of 5) are under 40%: nothing to slip from watch either
        assertThat(h.engine.afterResponse("all")).isEmpty();
        assertThat(h.engine.standing("all", "id-a", "m-1").rungName()).isEqualTo("watch");
    }

    @Test
    void aSlipToTheRungBelowGoesOneStepAndTheReasonStartsWithSlip() {
        LadderHarness h = new LadderHarness(AUTO);
        h.engine.standing("all", "id-a", "m-1");
        h.answeredMany(5, "all", "approve", "approve");
        h.engine.afterResponse("all");                                      // suggest
        for (int i = 0; i < 5; i++) h.answered("all", "approve", "reject", "suggest", "id-a", 1, Map.of());
        List<LadderEngine.Change> changes = h.engine.afterResponse("all");
        assertThat(changes).extracting(LadderEngine.Change::kind).containsExactly("demoted");
        assertThat(changes.get(0).to()).isEqualTo("watch");
        assertThat(changes.get(0).reason()).startsWith("slip: ").contains("history.last(5).rate(agrees) < 0.4");
        assertThat(h.engine.standing("all", "id-a", "m-1").version()).isEqualTo(3);
    }

    // ---------- by hand ----------

    @Test
    void aHandMoveIsCheckedStepByStepAndRecordsWhoAndWhy() {
        LadderHarness h = new LadderHarness(AUTO);
        h.engine.standing("all", "id-a", "m-1");
        h.answeredMany(5, "all", "approve", "approve");                      // supports suggest, not act
        assertThatThrownBy(() -> h.engine.set("all", "act", false, "x", "ops")).hasMessageContaining("not supported by the evidence").hasMessageContaining("--force");
        LadderEngine.Change up = h.engine.set("all", "suggest", false, "supported", "ops");
        assertThat(up.forced()).isFalse();
        assertThat(up.by()).isEqualTo("ops");
        assertThat(h.engine.standing("all", "id-a", "m-1").reason()).isEqualTo("supported");
        assertThat(h.engine.standing("all", "id-a", "m-1").version()).isEqualTo(2);
        LadderEngine.Change same = h.engine.set("all", "suggest", false, "nothing to do", "ops");
        assertThat(same.from()).isEqualTo(same.to());
        LadderEngine.Change forced = h.engine.set("all", "act", true, "drill", "ops");
        assertThat(forced.forced()).isTrue();
        assertThat(h.engine.standing("all", "id-a", "m-1").forced()).isTrue();
    }

    @Test
    void aForcedStandingStaysForcedWhenOnlyTheRulesAreRecheckedAndIsClearedByAnyRealMove() {
        LadderHarness h = new LadderHarness(AUTO);
        h.engine.standing("all", "id-a", "m-1");
        h.engine.set("all", "suggest", true, "drill", "ops");
        LadderEngine same = h.restart(AUTO.replace("climb when history.count >= 10", "climb when history.count >= 11"));
        assertThat(same.reevaluate()).extracting(LadderEngine.Change::kind).containsExactly("demoted");   // no record supports the forced rung
        assertThat(same.standing("all", "id-a", "m-1").forced()).isFalse();
    }

    // ---------- a change of rules ----------

    @Test
    void aTightenedLadderDemotesAsFarAsTheRecordSupportsAndSaysWhichConditionsFailed() {
        LadderHarness h = new LadderHarness(AUTO);
        h.engine.standing("all", "id-a", "m-1");
        h.answeredMany(5, "all", "approve", "approve");
        h.engine.afterResponse("all");
        h.answeredMany(5, "all", "approve", "approve");
        h.engine.afterResponse("all");
        assertThat(h.engine.standing("all", "id-a", "m-1").rungName()).isEqualTo("act");
        // both climbs now ask for more than the record holds
        String stricter = AUTO.replace("history.count >= 10 and", "history.count >= 99 and").replace("history.count >= 5 and", "history.count >= 98 and");
        LadderEngine tight = h.restart(stricter);
        List<LadderEngine.Change> changes = tight.reevaluate();
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).from()).isEqualTo("act");
        assertThat(changes.get(0).to()).isEqualTo("watch");
        assertThat(changes.get(0).reason()).startsWith("rules changed: ").contains("history.count >= 99").contains("history.count >= 98");
        assertThat(tight.standing("all", "id-a", "m-1").version()).isEqualTo(4);
        assertThat(tight.reevaluate()).isEmpty();
    }

    @Test
    void aSlipThatNowHoldsDemotesWhenTheRulesAreRechecked() {
        LadderHarness h = new LadderHarness(AUTO);
        h.engine.standing("all", "id-a", "m-1");
        h.answeredMany(5, "all", "approve", "approve");
        h.engine.afterResponse("all");
        assertThat(h.engine.standing("all", "id-a", "m-1").rungName()).isEqualTo("suggest");
        LadderEngine changed = h.restart(AUTO.replace("slip when history.last(5).rate(agrees) < 0.4", "slip when history.count >= 1"));
        List<LadderEngine.Change> changes = changed.reevaluate();
        assertThat(changes).extracting(LadderEngine.Change::kind).containsExactly("demoted");
        assertThat(changes.get(0).reason()).contains("history.count >= 1 [5 vs 1]");
    }

    @Test
    void everyScopeIsRecheckedAndOnlyThoseThatNoLongerQualifyMove() {
        LadderHarness h = new LadderHarness(AUTO);
        h.engine.standing("a", "id-a", "m-1");
        h.engine.standing("b", "id-a", "m-1");
        h.answeredMany(5, "a", "approve", "approve");
        h.engine.afterResponse("a");
        h.answeredMany(5, "b", "approve", "approve");
        h.engine.afterResponse("b");
        h.answeredMany(1, "b", "approve", "approve");
        LadderEngine changed = h.restart(AUTO.replace("climb when history.count >= 5 and", "climb when history.count >= 6 and"));
        List<LadderEngine.Change> changes = changed.reevaluate();
        assertThat(changes).extracting(LadderEngine.Change::scope).containsExactly("a");
        assertThat(changed.standing("b", "id-a", "m-1").rungName()).isEqualTo("suggest");
    }

    // ---------- epochs ----------

    @Test
    void testingOnPastCasesUsesTheInheritanceWhenOneIsGivenAndNeverMoreThanBefore() {
        LadderHarness h = new LadderHarness(LadderEngineTest.script("climbing is automatic", "act", "test it on past cases"));
        h.engine.standing("all", "id-a", "m-1");
        h.answeredMany(5, "all", "approve", "approve");
        h.engine.afterResponse("all");                                      // suggest
        LadderEngine none = h.restart(LadderEngineTest.script("climbing is automatic", "act", "test it on past cases"));
        LevelLog.Standing noReplay = none.standing("all", "id-b", "m-1");
        assertThat(noReplay.rungName()).isEqualTo("watch");
        assertThat(noReplay.reason()).contains("start over (no replay available)");

        LadderHarness g = new LadderHarness(LadderEngineTest.script("climbing is automatic", "act", "test it on past cases"));
        g.engine.standing("all", "id-a", "m-1");
        g.answeredMany(5, "all", "approve", "approve");
        g.engine.afterResponse("all");
        g.engine.inheritWith(old -> 2);                                     // would give act, but never more than the old standing
        LevelLog.Standing capped = g.engine.standing("all", "id-b", "m-1");
        assertThat(capped.rungName()).isEqualTo("suggest");
        assertThat(capped.reason()).contains("test it on past cases");
        assertThat(capped.epoch()).isEqualTo(2);
        assertThat(capped.version()).isEqualTo(3);
    }

    @Test
    void theFirstTimeAProviderNamesTheModelItIsRememberedWithoutAnEpoch() {
        LadderHarness h = new LadderHarness(AUTO);
        LevelLog.Standing first = h.engine.standing("all", "id-a", null);
        assertThat(first.servedModel()).isNull();
        LevelLog.Standing named = h.engine.standing("all", "id-a", "m-9");
        assertThat(named.servedModel()).isEqualTo("m-9");
        assertThat(named.epoch()).isEqualTo(1);
        assertThat(named.version()).isEqualTo(2);
        assertThat(named.reason()).isEqualTo(first.reason());
        assertThat(h.engine.standing("all", "id-a", "m-9").version()).isEqualTo(2);
        assertThat(h.ledger.recordsOfKind("D", Rec.LEVEL).get(1).str("kind")).isEqualTo("model");
        assertThat(h.engine.standing("all", "id-a", "").servedModel()).isEqualTo("m-9");
        assertThat(h.engine.standing("all", "id-a", "  ").servedModel()).isEqualTo("m-9");
        assertThat(h.engine.standing("all", "id-a", "unreported").epoch()).isEqualTo(1);
    }

    @Test
    void anEpochNamesBothAgentsBriefly() {
        LadderHarness h = new LadderHarness(AUTO);
        h.engine.standing("all", "0123456789abcdef", "m-1");
        LevelLog.Standing s = h.engine.standing("all", "fedcba9876543210", "m-1");
        assertThat(s.reason()).contains("01234567 to fedcba98").doesNotContain("0123456789abcdef");
        LevelLog.Standing short1 = h.engine.standing("all", "ab", "m-1");
        assertThat(short1.reason()).contains("fedcba98 to ab");
    }

    // ---------- planning ----------

    @Test
    void theCeilingAndAFreezeAreNamedAsTheReasonACaseRunsLower() {
        LadderHarness h = new LadderHarness(AUTO);
        h.engine.standing("all", "id-a", "m-1");
        h.answeredMany(5, "all", "approve", "approve");
        h.engine.afterResponse("all");
        h.answeredMany(5, "all", "approve", "approve");
        h.engine.afterResponse("all");                                      // act
        LevelLog.Standing s = h.engine.standing("all", "id-a", "m-1");
        V.Cs history = h.engine.history("all", 1);
        V.Rec rec = V.rec(Map.of("amount", V.num(10)));
        assertThat(h.engine.plan("all", "x", s, rec, history, h.clock.millis()).how()).isIn("act", "sample");
        LadderHarness lowered = new LadderHarness(LadderEngineTest.script("climbing is automatic", "suggest", "start over"));
        LadderEngine capped = new LadderEngine(lowered.policy, h.ledger, h.clock);
        LadderEngine.Plan p = capped.plan("all", "x", s, rec, history, h.clock.millis());
        assertThat(p.rung()).isEqualTo("suggest");
        assertThat(p.mode()).isEqualTo(RungDef.Mode.CONFIRM);
        assertThat(p.how()).isEqualTo("ceiling");
    }

    @Test
    void aFreezeNeverLowersAScopeThatIsAlreadyBelowTheAutonomousRungs() {
        LadderHarness h = new LadderHarness(AUTO);
        LevelLog.Standing s = h.engine.standing("all", "id-a", "m-1");
        h.engine.freeze("incident", "ops");
        assertThat(h.engine.effective(s)).isZero();
        h.answeredMany(5, "all", "approve", "approve");
        h.engine.afterResponse("all");
        LevelLog.Standing suggest = h.engine.standing("all", "id-a", "m-1");
        assertThat(h.engine.effective(suggest)).isEqualTo(1);
        V.Rec rec = V.rec(Map.of());
        assertThat(h.engine.plan("all", "x", suggest, rec, h.engine.history("all", 1), h.clock.millis()).how()).isEqualTo("suggest");
    }

    // ---------- the record ----------

    @Test
    void theFoldedCasesAreKeptUntilTheLedgerGainsARecord() {
        LadderHarness h = new LadderHarness(AUTO);
        h.engine.standing("all", "id-a", "m-1");
        List<LCase> a = h.engine.cases();
        assertThat(h.engine.cases()).isSameAs(a);
        h.answered("all", "approve", "approve");
        assertThat(h.engine.cases()).isNotSameAs(a).hasSize(1);
        assertThat(h.engine.historyAll("all").cases()).hasSize(1);
        assertThat(h.engine.history("elsewhere", 1).cases()).isEmpty();
    }

    @Test
    void anOutcomeIsRecordedOnceForTheSameFactAndAgainForADifferentOne() {
        LadderHarness h = new LadderHarness(AUTO);
        h.engine.standing("all", "id-a", "m-1");
        String id = h.answered("all", "approve", "approve");
        h.engine.outcome(id, "views", 5, "a");
        h.engine.outcome(id, "views", 5, "a");
        h.engine.outcome(id, "views", 6, "a");
        assertThat(h.ledger.recordsOfKind("D", Rec.OUTCOME)).hasSize(2);
        assertThat(h.engine.cases().get(0).latestOutcome()).isEqualTo("views");
    }

    @Test
    void theLogReadsEveryScopeAndAFreezeIsTheLatestWord() {
        LadderHarness h = new LadderHarness(AUTO);
        h.engine.standing("a", "id-a", "m-1");
        h.engine.standing("b", "id-a", "m-1");
        assertThat(h.engine.log().all()).containsOnlyKeys("a", "b");
        assertThat(h.engine.log().current("a")).isPresent();
        assertThat(h.engine.log().current("c")).isEmpty();
        h.engine.freeze("one", "ops");
        h.engine.freeze("two", "ops");
        h.engine.unfreeze("done", "ops");
        assertThat(h.engine.frozen()).isFalse();
        h.engine.freeze("again", "ops");
        assertThat(h.engine.frozen()).isTrue();
        assertThat(h.engine.log().write("a", 99, h.engine.log().current("a").orElseThrow(), "x", "y", "z", h.clock.instant(), "")).as("a stale version").isFalse();
    }
}
