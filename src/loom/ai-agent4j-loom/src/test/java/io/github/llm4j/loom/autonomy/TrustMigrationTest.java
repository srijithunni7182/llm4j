package io.github.llm4j.loom.autonomy;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.DecisionDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ladder.LadderEngine;
import io.github.llm4j.loom.ladder.LadderPolicy;
import io.github.llm4j.loom.ladder.LevelLog;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A deployment that has been running on the original engine: its ledger holds level records without
 * a fingerprint of rules. Moving to the ladder engine must keep every scope exactly where it
 * stands, and judge only what comes after.
 */
@Tag("L7")
class TrustMigrationTest {

    static final String TRUST =
            "never go above act  to suggest: after 5 cases, agreeing at least 60%  to act: after 10 cases, agreeing at least 80%  "
                    + "drop to suggest when 2 dangerous mistakes in 10 cases  moving up is automatic";

    private static LoomScript script() {
        return new LoomParser(new Lexer(TrustAsLadderEquivalenceTest.script(TRUST)).tokenize())
                .parseScript();
    }

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-05T09:00:00Z"));
    private final MemoryLedger ledger = new MemoryLedger();

    /**
     * Cases decided by the original engine at the given level, then its own bookkeeping after each.
     */
    private void original(
            Engine engine, String scope, int n, String proposed, String decided, boolean blind) {
        for (int i = 0; i < n; i++) {
            clock.advance(Duration.ofHours(2));
            String id = scope + "-" + ledger.records("D").size();
            Instant at = clock.instant();
            ledger.append(
                    new Rec(
                            id + "#case#1",
                            "D",
                            Rec.CASE,
                            at,
                            id,
                            1,
                            Rec.map(
                                    "scope",
                                    scope,
                                    "level",
                                    "watch",
                                    "identity",
                                    "id-a",
                                    "epoch",
                                    1,
                                    "fields",
                                    Map.of("tier", scope))));
            ledger.append(
                    new Rec(
                            id + "#proposed#1",
                            "D",
                            Rec.PROPOSED,
                            at,
                            id,
                            1,
                            Rec.map("choice", proposed)));
            ledger.append(
                    new Rec(
                            id + "#decided#1",
                            "D",
                            Rec.DECIDED,
                            at.plusSeconds(5),
                            id,
                            1,
                            Rec.map(
                                    "verdict", decided, "decider", "lead", "shown", !blind,
                                    "millis", 5000L)));
            engine.afterCase(scope);
        }
    }

    @Test
    void
            aLedgerTheOriginalEngineWroteIsAdoptedAtEveryLevelItReachedAndNothingIsDemotedByTheChange() {
        LoomScript s = script();
        DecisionDef d = s.getDecisions().get(0);
        MemoryLevelStore levels = new MemoryLevelStore();
        Engine old = new Engine(d, ledger, levels, clock);
        old.state("gold", "id-a");
        old.state("basic", "id-a");
        old.state("new", "id-a");
        original(old, "gold", 20, "approve", "approve", true);
        original(old, "basic", 6, "approve", "approve", true);
        assertThat(levels.get("D", "gold").orElseThrow().level()).isEqualTo(Level.ACT);
        assertThat(levels.get("D", "basic").orElseThrow().level()).isEqualTo(Level.SUGGEST);
        // a level granted by hand that the evidence does not support, as an operator may have done
        // in an emergency
        old.set("new", Level.ACT, true, "emergency", "ops");
        // and an agent that has since had a bad week the original engine had not yet acted on (no
        // drop rule had been met)
        original(
                old, "gold", 3, "approve", "reject",
                true); // 3 dangerous mistakes among the latest 10: the drop rule met, so the engine
        // already acted
        Level goldNow = levels.get("D", "gold").orElseThrow().level();

        // the same ledger, the ladder engine
        s.applyTrustLadders();
        LadderEngine engine = new LadderEngine(LadderPolicy.of(s, d), ledger, clock);
        assertThat(engine.log().current("gold").orElseThrow().rungName()).isEqualTo(goldNow.word());
        assertThat(engine.log().current("basic").orElseThrow().rungName()).isEqualTo("suggest");
        assertThat(engine.log().current("new").orElseThrow().rungName()).isEqualTo("act");
        assertThat(engine.log().current("new").orElseThrow().forced())
                .as("a forced standing stays forced")
                .isTrue();
        assertThat(engine.log().current("gold").orElseThrow().epoch())
                .isEqualTo(levels.get("D", "gold").orElseThrow().epoch());
        assertThat(engine.log().current("gold").orElseThrow().identity()).isEqualTo("id-a");

        List<LadderEngine.Change> changes = engine.reevaluate();
        assertThat(changes).as("changing engine moves no one").isEmpty();
        assertThat(engine.log().current("new").orElseThrow().rungName())
                .as("not even the one the evidence does not support")
                .isEqualTo("act");
        assertThat(engine.log().current("basic").orElseThrow().reason())
                .isEqualTo("adopted from the original engine");
        assertThat(engine.log().current("basic").orElseThrow().rules())
                .isEqualTo(LadderPolicy.of(s, d).fingerprint());
        assertThat(engine.reevaluate()).as("and it is done once").isEmpty();
        assertThat(
                        ledger.recordsOfKind("D", Rec.LEVEL).stream()
                                .filter(
                                        r ->
                                                "adopted from the original engine"
                                                        .equals(r.str("reason"))))
                .hasSize(3);
    }

    @Test
    void afterAdoptionTheLadderGoesOnFromWhereTheOriginalEngineLeftOff() {
        LoomScript s = script();
        DecisionDef d = s.getDecisions().get(0);
        Engine old = new Engine(d, ledger, new MemoryLevelStore(), clock);
        old.state("gold", "id-a");
        original(old, "gold", 6, "approve", "approve", true); // suggest

        s.applyTrustLadders();
        LadderEngine engine = new LadderEngine(LadderPolicy.of(s, d), ledger, clock);
        engine.reevaluate();
        assertThat(engine.log().current("gold").orElseThrow().rungName()).isEqualTo("suggest");

        // ten more agreeing cases answered blind (a sampled share, as it was at suggest), written
        // the way the ladder writes them
        for (int i = 0; i < 12; i++) {
            clock.advance(Duration.ofHours(2));
            String id = "later-" + i;
            Instant at = clock.instant();
            ledger.append(
                    new Rec(
                            id + "#case#1",
                            "D",
                            Rec.CASE,
                            at,
                            id,
                            1,
                            Rec.map(
                                    "scope",
                                    "gold",
                                    "level",
                                    "suggest",
                                    "how",
                                    "sample",
                                    "identity",
                                    "id-a",
                                    "epoch",
                                    1)));
            ledger.append(
                    new Rec(
                            id + "#proposed#1",
                            "D",
                            Rec.PROPOSED,
                            at,
                            id,
                            1,
                            Rec.map("choice", "approve")));
            ledger.append(
                    new Rec(
                            id + "#responded#1",
                            "D",
                            Rec.RESPONDED,
                            at.plusSeconds(5),
                            id,
                            1,
                            Rec.map(
                                    "kind", "accept", "choice", "approve", "by", "lead", "shown",
                                    false)));
            engine.afterResponse("gold");
        }
        assertThat(engine.log().current("gold").orElseThrow().rungName()).isEqualTo("act");
        assertThat(engine.log().current("gold").orElseThrow().version()).isGreaterThan(3);
    }

    @Test
    void aStandingTheOriginalEngineKeptInTheLedgerIsReadWithItsEpochAndIdentity() {
        LoomScript s = script();
        DecisionDef d = s.getDecisions().get(0);
        Engine old = new Engine(d, ledger, new MemoryLevelStore(), clock);
        LevelState first = old.state("gold", "id-a");
        old.newEpoch("gold", first, "id-b", Level.WATCH, "the agent changed");
        s.applyTrustLadders();
        LevelLog log = new LevelLog("D", List.of("watch", "suggest", "act"), ledger);
        assertThat(log.current("gold").orElseThrow().epoch()).isEqualTo(2);
        assertThat(log.current("gold").orElseThrow().identity()).isEqualTo("id-b");
        assertThat(log.current("gold").orElseThrow().rules()).isEqualTo(LevelLog.LEGACY);
    }

    @Test
    void
            editingATrustBlockKeepsEveryScopeWhereItIsAndOnlyTheDropRulesAndApprovalMoveItAfterwards() {
        LoomScript s = script();
        DecisionDef d = s.getDecisions().get(0);
        Engine old = new Engine(d, ledger, new MemoryLevelStore(), clock);
        old.state("gold", "id-a");
        original(old, "gold", 20, "approve", "approve", true); // act
        assertThat(old.levels().get("D", "gold").orElseThrow().level()).isEqualTo(Level.ACT);
        s.applyTrustLadders();
        LadderEngine engine = new LadderEngine(LadderPolicy.of(s, d), ledger, clock);
        engine.reevaluate();

        // the publisher tightens both thresholds far beyond what the record shows
        LoomScript edited =
                new LoomParser(
                                new Lexer(
                                                TrustAsLadderEquivalenceTest.script(
                                                        TRUST.replace(
                                                                        "after 5 cases, agreeing at least 60%",
                                                                        "after 500 cases, agreeing at least 99%")
                                                                .replace(
                                                                        "after 10 cases, agreeing at least 80%",
                                                                        "after 900 cases, agreeing at least 99%")))
                                        .tokenize())
                        .parseScript();
        edited.applyTrustLadders();
        LadderEngine after =
                new LadderEngine(
                        LadderPolicy.of(edited, edited.getDecisions().get(0)), ledger, clock);
        assertThat(after.reevaluate()).as("an edit moves nobody").isEmpty();
        assertThat(after.log().current("gold").orElseThrow().rungName()).isEqualTo("act");
        assertThat(after.log().current("gold").orElseThrow().reason())
                .contains("edited")
                .contains("kept");
        assertThat(after.log().current("gold").orElseThrow().rules())
                .isEqualTo(LadderPolicy.of(edited, edited.getDecisions().get(0)).fingerprint());
    }
}
