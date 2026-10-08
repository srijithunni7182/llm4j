package io.github.llm4j.loom.autonomy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.ast.DecisionDef;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Every way a level is moved, refused or explained (spec loom-earned-autonomy R3, R5). */
class EngineTest {

    private final MemoryLedger ledger = new MemoryLedger();
    private final MemoryLevelStore levels = new MemoryLevelStore();
    private final MutableClock clock = new MutableClock(DecideHarness.T0.plus(Duration.ofDays(60)));
    private final Seed seed = new Seed();

    private DecisionDef def(String trust) {
        return DecisionParseTest.parse(Scripts.AGENT + "decision Refund { proposed by: Triager choices: approve, reject, escalate ask: lead dangerous mistake: propose approve, person decides reject\n trust { " + trust + " } }")
                .getDecisions().get(0);
    }

    private Engine engine(String trust) {
        return new Engine(def(trust), ledger, levels, clock);
    }

    private static final String LADDER = "never go above act to suggest: after 3 cases, agreeing at least 30% to act: after 5 cases, agreeing at least 30%, with no dangerous mistakes moving up needs approval from: boss";

    private void blind(int n, String proposal, String verdict) {
        for (int i = 0; i < n; i++) seed.blind(ledger, "Refund", "s", 1, proposal, verdict, DecideHarness.T0);
    }

    @Test
    @Tag("EA-V5.3")
    void aHandSetLevelUpNeedsForceBeyondTheCeilingOrTheEvidenceAndComingDownNeverDoes() {
        Engine e = engine("never go above suggest to suggest: after 3 cases, agreeing at least 30% to act: after 5 cases, agreeing at least 30% moving up is automatic");
        e.state("s", "id1");
        assertThatThrownBy(() -> e.set("nowhere", Level.SUGGEST, false, "r", "ada")).hasMessageContaining("no such scope");
        assertThatThrownBy(() -> e.set("s", Level.SUGGEST, false, "r", "ada")).hasMessageContaining("not supported by the evidence").hasMessageContaining("--force");
        blind(4, "approve", "approve");
        assertThat(e.set("s", Level.SUGGEST, false, "enough evidence", "ada").forced()).isFalse();
        assertThatThrownBy(() -> e.set("s", Level.ACT, false, "r", "ada")).hasMessageContaining("above the ceiling");
        Engine.Change forced = e.set("s", Level.ACT, true, "emergency", "ada");
        assertThat(forced.forced()).isTrue();
        assertThat(levels.get("Refund", "s").orElseThrow().forced()).isTrue();
        assertThat(e.unearned("s", levels.get("Refund", "s").orElseThrow())).isTrue();
        assertThat(forced.sentence()).contains("forced").contains("emergency");

        Engine.Change down = e.set("s", Level.WATCH, false, "back", "ada");
        assertThat(down.forced()).isFalse();
        assertThat(e.unearned("s", levels.get("Refund", "s").orElseThrow())).isFalse();
    }

    @Test
    @Tag("EA-V5.3")
    void aForcedLevelIsNoLongerForcedOnceTheEvidenceSupportsEveryStepUpToIt() {
        Engine e = engine("never go above act to suggest: after 3 cases, agreeing at least 30% to act: after 5 cases, agreeing at least 30% moving up is automatic");
        e.state("s", "id1");
        e.set("s", Level.ACT, true, "emergency", "ada");
        assertThat(e.unearned("s", levels.get("Refund", "s").orElseThrow())).isTrue();
        blind(6, "approve", "approve");
        assertThat(e.unearned("s", levels.get("Refund", "s").orElseThrow())).isFalse();
    }

    @Test
    @Tag("EA-V3.9")
    void approvingOrRejectingNeedsAnOpenProposalTheNamedApproverAndAScopeThatStillNeedsIt() {
        Engine e = engine(LADDER);
        e.state("s", "id1");
        assertThatThrownBy(() -> e.approve("s", "boss", "ok")).hasMessageContaining("no open promotion proposal for scope s");
        assertThatThrownBy(() -> e.reject("s", "boss", "ok")).hasMessageContaining("no open promotion proposal");
        blind(4, "approve", "approve");
        assertThat(e.afterCase("s")).extracting(Engine.Change::kind).containsExactly("proposed");
        assertThat(e.afterCase("s")).as("one proposal, not two").isEmpty();
        assertThat(e.openProposal("s")).isPresent();

        assertThatThrownBy(() -> e.reject("s", "intern", "no")).hasMessageContaining("only boss");
        Engine.Change rejected = e.reject("s", "boss", "not yet");
        assertThat(rejected.kind()).isEqualTo("rejected");
        assertThat(rejected.sentence()).contains("rejected").contains("not yet");
        assertThat(e.openProposal("s")).isEmpty();
        assertThat(e.afterCase("s")).as("not asked again until the evidence has grown").isEmpty();
        blind(1, "approve", "approve");
        assertThat(e.afterCase("s")).extracting(Engine.Change::kind).containsExactly("proposed");

        LevelState state = levels.get("Refund", "s").orElseThrow();
        levels.compareAndSet("Refund", "s", state, state.withLevel(Level.SUGGEST, false, clock.instant(), "moved meanwhile"));
        assertThatThrownBy(() -> e.approve("s", "boss", "late")).hasMessageContaining("already at suggest");
    }

    @Test
    @Tag("EA-V3.9")
    void aStepUpThatADemotionRuleWouldUndoAtOnceIsNotMade() {
        Engine e = engine("never go above act to suggest: after 3 cases, agreeing at least 30% drop to watch when 2 unusable proposals in 5 cases moving up is automatic");
        e.state("s", "id1");
        blind(3, "approve", "approve");
        seed.malformed(ledger, "Refund", "s", 1, DecideHarness.T0);
        seed.malformed(ledger, "Refund", "s", 1, DecideHarness.T0);
        assertThat(e.afterCase("s")).as("promoted, then demoted at once: neither").isEmpty();
        assertThat(levels.get("Refund", "s").orElseThrow().level()).isEqualTo(Level.WATCH);
    }

    @Test
    @Tag("EA-V6.2")
    void aNewEpochStartsOnlyForTheCallerThatSawTheCurrentState() {
        Engine e = engine(LADDER);
        LevelState first = e.state("s", "id1");
        Engine.Change change = e.newEpoch("s", first, "id2", Level.WATCH, "the agent changed");
        assertThat(change.kind()).isEqualTo("epoch");
        assertThat(levels.get("Refund", "s").orElseThrow().epoch()).isEqualTo(2);
        assertThat(e.newEpoch("s", first, "id3", Level.WATCH, "a slower caller")).as("beaten to it").isNull();
        assertThat(levels.get("Refund", "s").orElseThrow().identity()).isEqualTo("id2");
    }

    @Test
    @Tag("EA-V3.11")
    void aFrozenDecisionRunsAtSuggestAtMostAndTheCeilingHoldsAnyLevel() {
        Engine e = engine("never go above suggest to suggest: after 3 cases, agreeing at least 30% moving up is automatic");
        e.state("s", "id1");
        LevelState act = new LevelState(Level.ACT, 1, "id1", true, clock.instant(), "forced", 2);
        assertThat(e.effective(act)).as("held at the ceiling").isEqualTo(Level.SUGGEST);
        Engine open = engine("never go above act to suggest: after 3 cases, agreeing at least 30% to act: after 3 cases, agreeing at least 30% moving up is automatic");
        assertThat(open.effective(act)).isEqualTo(Level.ACT);
        open.freeze("Refund", "incident");
        assertThat(open.effective(act)).isEqualTo(Level.SUGGEST);
        open.unfreeze("Refund", "over");
        assertThat(open.effective(act)).isEqualTo(Level.ACT);
        Engine.freeze(ledger, levels, clock, "Refund", "*", "everything");
        assertThat(open.effective(act)).as("a freeze of everything").isEqualTo(Level.SUGGEST);
        assertThat(ledger.recordsOfKind("Refund", Rec.FROZEN)).extracting(r -> r.flag("frozen")).containsExactly(true, false, true);
    }

    @Test
    @Tag("EA-V5.5")
    void anOutcomeAboutAnUnknownCaseIsAnErrorAndOneAboutARealCaseMayDemote() {
        Engine e = engine("never go above act to suggest: after 3 cases, agreeing at least 30% to act: after 3 cases, agreeing at least 30% drop to watch when 1 reversals in 5 cases moving up is automatic");
        e.state("s", "id1");
        assertThatThrownBy(() -> e.outcome("nope", "reversed", null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("there is no case nope");
        blind(4, "approve", "approve");
        e.afterCase("s");
        e.afterCase("s");
        LevelState now = levels.get("Refund", "s").orElseThrow();
        assertThat(now.level()).isEqualTo(Level.ACT);
        seed.byAgent(ledger, "Refund", "s", 1, "approve", DecideHarness.T0);
        List<Engine.Change> changes = e.outcome(seed.lastId(), "reversed", "chargeback");
        assertThat(changes).extracting(Engine.Change::kind).containsExactly("demoted");
        assertThat(changes.get(0).sentence()).contains("moved from act to watch").contains("demoted").contains("reversals");
    }

    @Test
    @Tag("EA-V3.7")
    void aChangeIsSentenceIsReadableForAWholeDecisionAndForAScope() {
        assertThat(new Engine.Change("promoted", "all", Level.WATCH, Level.SUGGEST, "why", "runtime", false).sentence()).startsWith("the decision moved from watch to suggest");
        assertThat(new Engine.Change("proposed", "gold", Level.WATCH, Level.ACT, "why", "runtime", false).sentence()).isEqualTo("promotion to act proposed for scope gold: why");
    }
}
