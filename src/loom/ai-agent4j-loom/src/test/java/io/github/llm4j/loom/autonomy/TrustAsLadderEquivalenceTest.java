package io.github.llm4j.loom.autonomy;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.DecisionDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ladder.LadderEngine;
import io.github.llm4j.loom.ladder.LadderPolicy;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The proof that a trust block can be written as a ladder: the same cases, in the same order, in two ledgers; the original engine moves a scope by
 * its trust block, the ladder engine by the ladder written from it, and after every case they must stand on the same rung and have done the same thing.
 */
@Tag("L7")
class TrustAsLadderEquivalenceTest {

    static final Instant START = Instant.parse("2026-01-05T09:00:00Z");

    static String script(String trust) {
        return """
                agent T { model: "m" system: "s" output_schema: { choice: string, reasoning: string } }
                decision D {
                    proposed by: T
                    choices: approve, reject, escalate
                    group cases by: tier
                    remember: amount, tier
                    dangerous mistake: propose approve, person decides reject
                    ask: lead
                    when the agent changes: start over
                    trust { %s }
                }
                """.formatted(trust);
    }

    static final List<String> TRUSTS = List.of(
            // 1: two steps, days, an automatic climb, dangerous mistakes in a window
            "never go above act  to suggest: after 20 cases over 2 days, agreeing at least 80%  "
                    + "to act: after 40 cases over 5 days, agreeing at least 90%, with no dangerous mistakes  judge on the latest 50 cases  "
                    + "drop to suggest when 2 dangerous mistakes in 20 cases  moving up is automatic",
            // 2: an approver, reversals dropping to watch, a floor on agreement
            "never go above act  to suggest: after 15 cases, agreeing at least 75%  to act: after 30 cases over 3 days, agreeing at least 85%, with at most 5% dangerous mistakes  "
                    + "drop to watch when 3 reversals in 30 cases  drop to suggest when agreement falls below 70%  moving up needs approval from: risk-owner",
            // 3: both targets on one rung, unusable proposals, a short window
            "never go above act  to suggest: after 10 cases, agreeing at least 70%  to act: after 25 cases, agreeing at least 80%  judge on the latest 30 cases  "
                    + "drop to suggest when 2 dangerous mistakes in 15 cases  drop to watch when 4 unusable proposals in 30 cases  moving up is automatic",
            // 4: the ceiling holds it at suggest (the default ceiling), a rule for act that can never be used
            "to suggest: after 12 cases over 3 days, agreeing at least 80%  to act: after 20 cases over 3 days, agreeing at least 90%  moving up is automatic",
            // 5: no rule for act at all
            "never go above act  to suggest: after 12 cases, agreeing at least 80%  moving up is automatic",
            // 6: a window shorter than the cases asked for: the climb can never happen
            "never go above act  to suggest: after 40 cases, agreeing at least 50%  judge on the latest 20 cases  moving up is automatic");

    private final java.util.Map<String, Integer> seen = new java.util.TreeMap<>();

    private record Side(LoomScript script, DecisionDef decision, MemoryLedger ledger, MutableClock clock) { }

    private static Side side(String text, MutableClock clock) {
        LoomScript s = new LoomParser(new Lexer(text).tokenize()).parseScript();
        return new Side(s, s.getDecisions().get(0), new MemoryLedger(), clock);
    }

    @Test
    void everyTrustBlockHereCanBeWrittenAsALadder() {
        for (String trust : TRUSTS) {
            LoomScript s = new LoomParser(new Lexer(script(trust)).tokenize()).parseScript();
            io.github.llm4j.loom.ladder.TrustDesugar.Result r = io.github.llm4j.loom.ladder.TrustDesugar.of(s.getDecisions().get(0), java.util.Set.of());
            assertThat(r.unsupported()).as(trust).isNull();
            assertThat(r.ladder().getRungs()).extracting(x -> x.getName()).containsExactly("watch", "suggest", "act");
        }
    }

    @Test
    void theLadderMovesWhereTheTrustBlockMovesOverRandomHistories() {
        int steps = 0;
        for (int t = 0; t < TRUSTS.size(); t++) {
            for (long seed = 1; seed <= 25; seed++) steps += run(TRUSTS.get(t), seed, "config " + (t + 1) + ", seed " + seed);
        }
        assertThat(steps).isGreaterThan(40_000);
        // the comparison means something only if the scopes really did climb to every rung, slip from each, and the agent really did act
        assertThat(seen.getOrDefault("promoted:suggest", 0)).isGreaterThan(20);
        assertThat(seen.getOrDefault("promoted:act", 0)).isGreaterThan(20);
        assertThat(seen.getOrDefault("proposed:suggest", 0) + seen.getOrDefault("proposed:act", 0)).isGreaterThan(10);
        assertThat(seen.getOrDefault("demoted:suggest", 0)).isGreaterThan(5);
        assertThat(seen.getOrDefault("demoted:watch", 0)).isGreaterThan(5);
        assertThat(seen.getOrDefault("acted", 0)).isGreaterThan(1000);
        System.out.println("equivalence coverage: " + seen);
    }

    private int run(String trust, long seed, String label) {
        MutableClock clock = new MutableClock(START);
        Side old = side(script(trust), clock);
        Side neu = side(script(trust), clock);
        neu.script().applyTrustLadders();
        assertThat(neu.decision().getLadder()).as(label).isNotNull();
        Engine original = new Engine(old.decision(), old.ledger(), new MemoryLevelStore(), clock);
        LadderEngine ladder = new LadderEngine(LadderPolicy.of(neu.script(), neu.decision()), neu.ledger(), clock);

        Random rnd = new Random(seed);
        String[] scopes = {"gold", "basic"};
        String[] choices = {"approve", "reject", "escalate"};
        double[] agree = {0.97, 0.97};
        int steps = 0;
        int acted = 0;
        for (int i = 0; i < 400; i++) {
            if (i % 60 == 0) for (int k = 0; k < 2; k++) agree[k] = rnd.nextBoolean() ? 0.97 : 0.55 + rnd.nextDouble() * 0.3;
            clock.advance(Duration.ofMinutes((20 + rnd.nextInt(60 * 10)) / (seed % 2 == 0 ? 20 : 1)));   // even seeds: cases arrive fast, so days are what holds the agent back
            int k = rnd.nextInt(2);
            String scope = scopes[k];
            LevelState state = original.state(scope, "id-a");
            ladder.standing(scope, "id-a", null);
            Level ranAt = state.level();
            boolean audit = ranAt != Level.WATCH && rnd.nextInt(100) < 35;
            boolean blind = ranAt == Level.WATCH || audit;

            String proposed = choices[rnd.nextInt(3)];
            boolean malformed = rnd.nextInt(100) < 4;
            if (malformed) proposed = "escalate";
            String decided = rnd.nextDouble() < agree[k] ? proposed : choices[(java.util.Arrays.asList(choices).indexOf(proposed) + 1 + rnd.nextInt(2)) % 3];
            boolean byAgent = ranAt == Level.ACT && !audit && !malformed;
            if (byAgent) {
                decided = proposed;
                acted++;
            }
            String id = "case-" + i;
            Instant at = clock.instant();
            List<Rec> recs = new ArrayList<>();
            recs.add(new Rec(id + "#case#1", "D", Rec.CASE, at, id, 1, Rec.map("scope", scope, "level", blind ? "watch" : ranAt.word(), "how", "x",
                    "fields", Map.of("amount", String.valueOf(rnd.nextInt(400)), "tier", scope), "identity", "id-a", "epoch", state.epoch())));
            recs.add(new Rec(id + "#proposed#1", "D", Rec.PROPOSED, at, id, 1,
                    Rec.map("choice", proposed, "reasoning", "r", "malformed", malformed ? true : null)));
            recs.add(new Rec(id + "#decided#1", "D", Rec.DECIDED, at.plusSeconds(30), id, 1,
                    Rec.map("verdict", decided, "decider", byAgent ? "agent" : "lead", "shown", !blind && !byAgent, "millis", 30000L)));
            if (byAgent && rnd.nextInt(100) < 6) {
                recs.add(new Rec(id + "#outcome#reversed#", "D", Rec.OUTCOME, at.plusSeconds(3600), id, 1, Rec.map("result", "reversed")));
            }
            for (Rec r : recs) {
                old.ledger().append(r);
                neu.ledger().append(r);
            }
            List<Engine.Change> before = original.afterCase(scope);
            List<LadderEngine.Change> after = ladder.afterResponse(scope);
            steps++;
            before.forEach(c -> seen.merge(c.kind() + ":" + c.to().word(), 1, Integer::sum));
            if (byAgent) seen.merge("acted", 1, Integer::sum);

            String where = label + ", case " + i + " (" + scope + ", ran at " + ranAt.word() + ")";
            assertThat(after.stream().map(c -> c.kind() + ":" + c.to()).toList()).as(where + " changes")
                    .isEqualTo(before.stream().map(c -> c.kind() + ":" + c.to().word()).toList());
            assertThat(ladder.log().current(scope).orElseThrow().rungName()).as(where + " rung")
                    .isEqualTo(original.state(scope, "id-a").level().word());

            // without automatic climbing a proposal waits for the approver; both are asked, and both are told yes
            Optional<Ladder.Proposal> openOld = original.openProposal(scope);
            Optional<LadderEngine.Proposal> openNew = ladder.openProposal(scope);
            assertThat(openNew.map(LadderEngine.Proposal::to)).as(where + " open proposal").isEqualTo(openOld.map(p -> p.to().word()));
            if (openOld.isPresent() && rnd.nextInt(100) < 60) {
                original.approve(scope, "risk-owner", "ok");
                ladder.approve(scope, "risk-owner", "ok");
                assertThat(ladder.log().current(scope).orElseThrow().rungName()).as(where + " after approval").isEqualTo(original.state(scope, "id-a").level().word());
            }
        }
        return steps;
    }
}
