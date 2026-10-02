package io.github.llm4j.loom.autonomy;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.DecisionDef;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The rules of the ladder applied to a seeded ledger, on both sides of every boundary (spec loom-earned-autonomy R3). */
class LadderTest {

    static final Instant T0 = Instant.parse("2026-03-01T00:00:00Z");

    private final MemoryLedger ledger = new MemoryLedger();
    private final Seed seed = new Seed();
    private final MutableClock clock = new MutableClock(T0.plus(Duration.ofDays(60)));

    private static DecisionDef decision(String trust) {
        return DecisionParseTest.parse(Scripts.AGENT + "decision Refund { proposed by: Triager choices: approve, reject, escalate dangerous mistake: propose approve, person decides reject\n trust { " + trust + " } }")
                .getDecisions().get(0);
    }

    private static LevelState at(Level level) {
        return new LevelState(level, 1, "id1", false, T0, "test", 1);
    }

    /** n cases decided blind, the first {@code wrong} of them disagreeing (reject proposed, approve decided: not dangerous), a day apart from {@code start}. */
    private void agreeing(int n, int wrong, int startDay) {
        for (int i = 0; i < n; i++) {
            boolean bad = i < wrong;
            seed.blind(ledger, "Refund", "gold", 1, bad ? "reject" : "approve", "approve", T0.plus(Duration.ofDays(startDay + i % 20)));
        }
    }

    @Test
    @Tag("EA-V3.3")
    void everyPartOfARuleMustHoldAndEachBlocksOnBothSidesOfItsBoundary() {
        DecisionDef d = decision("to suggest: after 100 cases over 14 days, agreeing at least 90%");
        Ladder ladder = new Ladder(d, ledger, clock);

        agreeing(99, 0, 0);
        Ladder.Progress shortByOne = ladder.progress("gold", at(Level.WATCH));
        assertThat(shortByOne.eligible()).isFalse();
        assertThat(shortByOne.firstMissing().orElseThrow().name()).isEqualTo("cases");
        assertThat(shortByOne.firstMissing().orElseThrow().toString()).contains("99 cases").contains("100 cases");

        agreeing(1, 0, 0);
        assertThat(ladder.progress("gold", at(Level.WATCH)).eligible()).as("all met").isTrue();

        clock.set(T0.plus(Duration.ofDays(13)).plusSeconds(3600 * 23)); // the first case is 13 days and 23 hours old
        Ladder.Progress shortOfDays = ladder.progress("gold", at(Level.WATCH));
        assertThat(shortOfDays.eligible()).isFalse();
        assertThat(shortOfDays.firstMissing().orElseThrow().name()).isEqualTo("days");
        clock.set(T0.plus(Duration.ofDays(14)));
        assertThat(ladder.progress("gold", at(Level.WATCH)).eligible()).isTrue();
    }

    @Test
    @Tag("EA-V3.3")
    void theAgreementPartIsJudgedByTheLowerBoundNotTheRawRate() {
        DecisionDef d = decision("to suggest: after 100 cases over 1 days, agreeing at least 90%");
        agreeing(100, 5, 0); // 95% raw, but the lower bound is about 88.9%
        Ladder.Progress p = new Ladder(d, ledger, clock).progress("gold", at(Level.WATCH));
        assertThat(p.figures().rate()).isEqualTo(0.95);
        assertThat(p.eligible()).isFalse();
        assertThat(p.firstMissing().orElseThrow().name()).isEqualTo("agreement");

        MemoryLedger better = new MemoryLedger();
        for (int i = 0; i < 100; i++) seed.blind(better, "Refund", "gold", 1, i < 2 ? "reject" : "approve", "approve", T0.plus(Duration.ofDays(i % 5)));
        assertThat(new Ladder(d, better, clock).progress("gold", at(Level.WATCH)).eligible()).as("98% raw clears 90%").isTrue();
    }

    @Test
    @Tag("EA-V3.4")
    void noDangerousMistakesBlocksWhileOneIsInTheWindowAndAllowsOnceItHasLeft() {
        DecisionDef d = decision("never go above act\n to suggest: after 5 cases over 1 days, agreeing at least 10%\n judge on the latest 30 cases\n to act: after 30 cases over 1 days, agreeing at least 50%, with no dangerous mistakes");
        seed.blind(ledger, "Refund", "gold", 1, "approve", "reject", T0); // dangerous
        for (int i = 0; i < 29; i++) seed.blind(ledger, "Refund", "gold", 1, "approve", "approve", T0.plus(Duration.ofDays(2)));
        Ladder ladder = new Ladder(d, ledger, clock);

        Ladder.Progress blocked = ladder.progress("gold", at(Level.SUGGEST));
        assertThat(blocked.eligible()).isFalse();
        assertThat(blocked.firstMissing().orElseThrow().name()).isEqualTo("dangerous mistakes");

        seed.blind(ledger, "Refund", "gold", 1, "approve", "approve", T0.plus(Duration.ofDays(3))); // the dangerous one leaves the 30-case window
        assertThat(ladder.progress("gold", at(Level.SUGGEST)).eligible()).isTrue();
    }

    @Test
    @Tag("EA-V3.3")
    void aCeilingOnDangerousMistakesAllowsAFewAndNoMore() {
        DecisionDef d = decision("never go above act\n to suggest: after 5 cases, agreeing at least 10%\n to act: after 10 cases, agreeing at least 50%, with at most 10% dangerous mistakes");
        for (int i = 0; i < 9; i++) seed.blind(ledger, "Refund", "gold", 1, "approve", "approve", T0);
        seed.blind(ledger, "Refund", "gold", 1, "approve", "reject", T0);
        Ladder ladder = new Ladder(d, ledger, clock);
        assertThat(ladder.progress("gold", at(Level.SUGGEST)).eligible()).as("1 in 10 is exactly 10%").isTrue();
        seed.blind(ledger, "Refund", "gold", 1, "approve", "reject", T0);
        assertThat(ladder.progress("gold", at(Level.SUGGEST)).eligible()).as("2 in 11 is over").isFalse();
    }

    @Test
    @Tag("EA-V4.2")
    void casesDecidedWithTheProposalInViewNeverCountTowardsAPromotion() {
        DecisionDef d = decision("to suggest: after 10 cases over 1 days, agreeing at least 50%");
        for (int i = 0; i < 1000; i++) seed.shown(ledger, "Refund", "gold", 1, "approve", "approve", T0);
        for (int i = 0; i < 1000; i++) seed.byAgent(ledger, "Refund", "gold", 1, "approve", T0);
        Ladder ladder = new Ladder(d, ledger, clock);
        assertThat(ladder.progress("gold", at(Level.WATCH)).eligible()).isFalse();
        assertThat(ladder.evidence("gold", 1)).isEmpty();
        agreeing(10, 0, 0);
        assertThat(ladder.evidence("gold", 1)).hasSize(10);
    }

    @Test
    @Tag("EA-V4.7")
    void evidenceFromAnotherEpochDoesNotCount() {
        DecisionDef d = decision("to suggest: after 10 cases, agreeing at least 50%");
        for (int i = 0; i < 20; i++) seed.blind(ledger, "Refund", "gold", 1, "approve", "approve", T0);
        Ladder ladder = new Ladder(d, ledger, clock);
        assertThat(ladder.progress("gold", at(Level.WATCH)).eligible()).isTrue();
        assertThat(ladder.progress("gold", new LevelState(Level.WATCH, 2, "id2", false, T0, "new agent", 1)).eligible()).isFalse();
        assertThat(ladder.evidence("gold", 2)).isEmpty();
    }

    @Test
    @Tag("EA-V3.8")
    void theCeilingCapsPromotionWhateverTheEvidenceSays() {
        String rules = "to suggest: after 5 cases, agreeing at least 10%\n to act: after 5 cases, agreeing at least 10%";
        agreeing(50, 0, 0);
        Ladder.Progress capped = new Ladder(decision(rules), ledger, clock).progress("gold", at(Level.SUGGEST));
        assertThat(capped.eligible()).isFalse();
        assertThat(capped.reasonNone()).contains("never go above suggest");
        assertThat(new Ladder(decision("never go above watch\n" + rules), ledger, clock).progress("gold", at(Level.WATCH)).reasonNone()).contains("never go above watch");
        assertThat(new Ladder(decision("never go above act\n" + rules), ledger, clock).progress("gold", at(Level.SUGGEST)).eligible()).isTrue();
        assertThat(new Ladder(decision("never go above act\n" + rules), ledger, clock).progress("gold", at(Level.ACT)).reasonNone()).isEqualTo("already at act");
        assertThat(new Ladder(decision("never go above act\n to suggest: after 5 cases, agreeing at least 10%"), ledger, clock).progress("gold", at(Level.SUGGEST)).reasonNone())
                .contains("no rule for moving up to act");
    }

    @Test
    @Tag("EA-V3.7")
    void eachDemotionRuleFiresAtItsBoundaryAndNotBefore() {
        DecisionDef d = decision("never go above act\n drop to suggest when 2 dangerous mistakes in 50 cases\n drop to watch when 2 reversals in 100 cases\n"
                + " drop to suggest when 5 unusable proposals in 50 cases\n drop to suggest when agreement falls below 80%");
        Ladder ladder = new Ladder(d, ledger, clock);
        assertThat(ladder.demotion("gold", at(Level.ACT))).isEmpty();

        seed.blind(ledger, "Refund", "gold", 1, "approve", "reject", T0);
        assertThat(ladder.demotion("gold", at(Level.ACT))).as("one dangerous mistake").isEmpty();
        seed.blind(ledger, "Refund", "gold", 1, "approve", "reject", T0);
        Ladder.Demotion dangerous = ladder.demotion("gold", at(Level.ACT)).orElseThrow();
        assertThat(dangerous.to()).isEqualTo(Level.SUGGEST);
        assertThat(dangerous.reason()).contains("2 dangerous mistakes in the last 2 cases");
        assertThat(ladder.demotion("gold", at(Level.SUGGEST))).as("already at that level").isEmpty();

        // the mistakes age out of a window of 50
        for (int i = 0; i < 50; i++) seed.blind(ledger, "Refund", "gold", 1, "approve", "approve", T0);
        assertThat(ladder.demotion("gold", at(Level.ACT))).isEmpty();
    }

    @Test
    @Tag("EA-V3.7")
    void reversalsAndUnusableProposalsAreCountedOverTheirOwnWindows() {
        DecisionDef d = decision("never go above act\n drop to watch when 2 reversals in 100 cases\n drop to suggest when 5 unusable proposals in 50 cases");
        Ladder ladder = new Ladder(d, ledger, clock);
        for (int i = 0; i < 4; i++) seed.byAgent(ledger, "Refund", "gold", 1, "approve", T0);
        String one = seed.lastId();
        ledger.append(new Rec(one + "#outcome#reversed", "Refund", Rec.OUTCOME, T0, one, 1, Rec.map("result", "reversed")));
        assertThat(ladder.demotion("gold", at(Level.ACT))).as("one reversal").isEmpty();
        seed.byAgent(ledger, "Refund", "gold", 1, "approve", T0);
        String two = seed.lastId();
        ledger.append(new Rec(two + "#outcome#reversed", "Refund", Rec.OUTCOME, T0, two, 1, Rec.map("result", "reversed")));
        assertThat(ladder.demotion("gold", at(Level.ACT)).orElseThrow().to()).isEqualTo(Level.WATCH);

        MemoryLedger other = new MemoryLedger();
        Ladder second = new Ladder(d, other, clock);
        for (int i = 0; i < 4; i++) seed.malformed(other, "Refund", "gold", 1, T0);
        assertThat(second.demotion("gold", at(Level.ACT))).isEmpty();
        seed.malformed(other, "Refund", "gold", 1, T0);
        assertThat(second.demotion("gold", at(Level.ACT)).orElseThrow().reason()).contains("5 unusable proposals");
    }

    @Test
    @Tag("EA-V3.7")
    void aFloorOnAgreementIsOnlyJudgedOnceThereAreEnoughCasesToMeanSomething() {
        DecisionDef d = decision("never go above act\n judge on the latest 40 cases\n drop to suggest when agreement falls below 80%");
        Ladder ladder = new Ladder(d, ledger, clock);
        for (int i = 0; i < Ladder.MIN_FOR_FLOOR - 1; i++) seed.blind(ledger, "Refund", "gold", 1, "reject", "approve", T0);
        assertThat(ladder.demotion("gold", at(Level.ACT))).isEmpty();
        seed.blind(ledger, "Refund", "gold", 1, "reject", "approve", T0);
        assertThat(ladder.demotion("gold", at(Level.ACT)).orElseThrow().reason()).contains("below 80%");
    }

    @Test
    @Tag("EA-V3.9")
    void aPromotionIsProposedOnceNotWhileOneIsOpenAndAfterARejectionOnlyWhenTheEvidenceHasGrown() {
        DecisionDef d = decision("to suggest: after 50 cases, agreeing at least 10%");
        Ladder ladder = new Ladder(d, ledger, clock);
        agreeing(50, 0, 0);
        LevelState state = at(Level.WATCH);
        Ladder.Progress progress = ladder.progress("gold", state);
        assertThat(ladder.proposalDue("gold", progress, state)).isTrue();

        ledger.append(new Rec("p1", "Refund", Rec.PROMOTION_PROPOSED, T0, "", 1, Rec.map("scope", "gold", "to", "suggest", "cases", 50)));
        assertThat(ladder.proposalDue("gold", progress, state)).as("one is open").isFalse();
        assertThat(ladder.proposals("gold")).extracting(Ladder.Proposal::open).containsExactly(true);

        ledger.append(new Rec("p1#no", "Refund", Rec.PROMOTION_DECIDED, T0, "", 1, Rec.map("proposal", "p1", "result", "rejected", "by", "risk-owner")));
        assertThat(ladder.proposals("gold").get(0).result()).isEqualTo("rejected");
        assertThat(ladder.proposals("gold").get(0).by()).isEqualTo("risk-owner");
        assertThat(ladder.proposalDue("gold", progress, state)).as("rejected, nothing new").isFalse();

        agreeing(9, 0, 0);
        assertThat(ladder.proposalDue("gold", ladder.progress("gold", state), state)).as("59 cases: under a fifth more").isFalse();
        agreeing(1, 0, 0);
        assertThat(ladder.proposalDue("gold", ladder.progress("gold", state), state)).as("60 cases: a fifth of 50 more").isTrue();
        assertThat(ladder.proposalDue("gold", new Ladder.Progress(null, false, java.util.List.of(), AgreementStats.Figures.EMPTY, "x"), state)).isFalse();
    }

    @Test
    @Tag("EA-V3.1")
    void scopesEarnSeparately() {
        DecisionDef d = decision("to suggest: after 10 cases, agreeing at least 50%");
        for (int i = 0; i < 20; i++) seed.blind(ledger, "Refund", "gold", 1, "approve", "approve", T0);
        for (int i = 0; i < 3; i++) seed.blind(ledger, "Refund", "basic", 1, "approve", "approve", T0);
        Ladder ladder = new Ladder(d, ledger, clock);
        assertThat(ladder.progress("gold", at(Level.WATCH)).eligible()).isTrue();
        assertThat(ladder.progress("basic", at(Level.WATCH)).eligible()).isFalse();
    }
}
