package io.github.llm4j.loom.autonomy;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.DecisionDef;
import java.time.Duration;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Random sequences of cases, outcomes, freezes and hand changes against a ladder, and the rules that must hold after every step
 * (spec loom-earned-autonomy V10.4). 20 000 sequences by default; {@code -Dloom.autonomy.sequences=100000} for the long run.
 */
class LadderPropertyTest {

    private static final int SEQUENCES = Integer.getInteger("loom.autonomy.sequences", 20_000);
    private static final long SEED = Long.getLong("loom.autonomy.seed", 20260301L);

    private static DecisionDef decision() {
        return DecisionParseTest.parse(Scripts.AGENT + """
                decision Refund {
                    proposed by: Triager
                    choices: approve, reject, escalate
                    dangerous mistake: propose approve, person decides reject
                    ask: lead
                    trust {
                        never go above act
                        to suggest: after 4 cases, agreeing at least 40%
                        to act: after 7 cases, agreeing at least 50%, with no dangerous mistakes
                        judge on the latest 12 cases
                        drop to suggest when 2 dangerous mistakes in 10 cases
                        drop to watch when 3 reversals in 10 cases
                        drop to suggest when 4 unusable proposals in 10 cases
                        moving up is automatic
                    }
                }
                """).getDecisions().get(0);
    }

    @Test
    @Tag("EA-V10.4")
    void whateverHappensALevelNeverExceedsItsCeilingNeverRisesWithoutAnEligibleRecordOrOnNonBlindEvidenceAndAlwaysFallsOnASatisfiedRule() {
        DecisionDef def = decision();
        Random random = new Random(SEED);
        for (int sequence = 0; sequence < SEQUENCES; sequence++) {
            MemoryLedger ledger = new MemoryLedger();
            MemoryLevelStore levels = new MemoryLevelStore();
            MutableClock clock = new MutableClock(DecideHarness.T0);
            Engine engine = new Engine(def, ledger, levels, clock);
            Seed seed = new Seed();
            engine.state("s", "id1");
            String lastAgentCase = null;
            for (int step = 0; step < 14; step++) {
                clock.advance(Duration.ofHours(1 + random.nextInt(5)));
                LevelState before = levels.get("Refund", "s").orElseThrow();
                int blindBefore = engine.ladder().evidence("s", before.epoch()).size();
                Ladder.Progress eligibleBefore = engine.ladder().progress("s", before);
                AgreementStats.Figures figuresBefore = engine.ladder().figures("s", before.epoch());
                String op;
                boolean afterCase = true;
                switch (random.nextInt(9)) {
                    case 0, 1, 2 -> {
                        op = "blind agreeing";
                        seed.blind(ledger, "Refund", "s", before.epoch(), "approve", "approve", clock.instant());
                    }
                    case 3 -> {
                        op = "blind disagreeing";
                        seed.blind(ledger, "Refund", "s", before.epoch(), "approve", random.nextBoolean() ? "reject" : "escalate", clock.instant());
                    }
                    case 4 -> {
                        op = "shown";
                        seed.shown(ledger, "Refund", "s", before.epoch(), "approve", "approve", clock.instant());
                    }
                    case 5 -> {
                        op = "by agent";
                        seed.byAgent(ledger, "Refund", "s", before.epoch(), "approve", clock.instant());
                        lastAgentCase = seed.lastId();
                    }
                    case 6 -> {
                        op = "unusable";
                        seed.malformed(ledger, "Refund", "s", before.epoch(), clock.instant());
                    }
                    case 7 -> {
                        op = "outcome";
                        if (lastAgentCase != null) engine.outcome("c" + lastAgentCase.substring(1), "reversed", "n" + step);
                        else seed.byAgent(ledger, "Refund", "s", before.epoch(), "approve", clock.instant());
                        afterCase = false;
                    }
                    default -> {
                        op = "freeze";
                        if (levels.frozen("Refund")) engine.unfreeze("Refund", "t"); else engine.freeze("Refund", "t");
                        afterCase = false;
                    }
                }
                if (op.equals("outcome") && lastAgentCase == null) lastAgentCase = seed.lastId();
                if (afterCase) engine.afterCase("s");

                LevelState after = levels.get("Refund", "s").orElseThrow();
                String at = "sequence " + sequence + " step " + step + " (" + op + "): " + before.level() + " -> " + after.level();

                // never above the ceiling, unless a person forced it
                assertThat(after.level().compareTo(def.getCeiling()) <= 0 || after.forced()).as(at + " ceiling").isTrue();
                // a rise is exactly one step, and only when the rule was met on blind evidence before this step's own cases were added
                if (after.level().compareTo(before.level()) > 0) {
                    assertThat(after.level().ordinal() - before.level().ordinal()).as(at + " one step").isEqualTo(1);
                    Ladder.Progress now = engine.ladder().evaluate("s", after.epoch(), after.level());
                    assertThat(now.eligible()).as(at + " eligible record").isTrue();
                }
                // non-blind evidence changes nothing about blind evidence
                if (op.equals("shown") || op.equals("by agent")) {
                    assertThat(engine.ladder().evidence("s", after.epoch())).as(at + " evidence").hasSize(blindBefore);
                    assertThat(engine.ladder().figures("s", after.epoch())).as(at + " the figures a promotion is judged on").isEqualTo(figuresBefore);
                }
                // after the ladder has looked, no demotion rule still holds for a level above its target
                if (afterCase) {
                    Optional<Ladder.Demotion> still = engine.ladder().demotion("s", after);
                    assertThat(still).as(at + " a satisfied demotion rule must have fired").isEmpty();
                }
                // frozen decisions never run above suggest
                if (levels.frozen("Refund")) assertThat(engine.effective(after).compareTo(Level.SUGGEST)).as(at + " frozen").isLessThanOrEqualTo(0);
                assertThat(eligibleBefore).isNotNull();
            }
        }
    }
}
