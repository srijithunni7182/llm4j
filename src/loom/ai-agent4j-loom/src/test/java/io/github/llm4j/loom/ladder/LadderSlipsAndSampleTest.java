package io.github.llm4j.loom.ladder;

import static io.github.llm4j.loom.ladder.LadderPolicyTest.NOW;
import static io.github.llm4j.loom.ladder.LadderPolicyTest.c;
import static io.github.llm4j.loom.ladder.LadderPolicyTest.history;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.DecisionDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.RungDef;
import io.github.llm4j.loom.autonomy.expr.V;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** A rung may have several ways down, each to its own rung; a confirm rung may be sampled blind. */
@Tag("L4")
class LadderSlipsAndSampleTest {

    static final String SCRIPT = """
            agent T { model: "m" system: "s" output_schema: { choice: string, reasoning: string } }
            measure agrees(c) = c.response.choice == c.proposal.choice
            ladder L {
                rung watch { mode: blind }
                rung suggest {
                    mode: confirm
                    climb when history.count >= 3
                    sample 40% blind
                }
                rung act {
                    mode: autonomous
                    climb when history.count >= 5
                    slip to suggest when history.last(20).rate(agrees) < 0.8
                    slip to watch when history.last(10).count(dangerous) >= 2
                }
                climbing is automatic
            }
            decision D {
                proposed by: T
                choices: approve, reject, escalate
                ask: lead
                dangerous mistake: propose approve, person decides reject
                ladder: L
            }
            """;

    static LadderPolicy policy(String script) {
        LoomScript s = new LoomParser(new Lexer(script).tokenize()).parseScript();
        DecisionDef d = s.getDecisions().get(0);
        return LadderPolicy.of(s, d);
    }

    static V.Cs dangerous(int agree, int dangerous) {
        List<V.Rec> h = new ArrayList<>(history(agree, 0).cases());
        for (int i = 0; i < dangerous; i++) h.add(c(0.1 * (i + 1), "approve", "reject"));
        return V.cases(h);
    }

    @Test
    void aRungKeepsEveryWayDownInTheOrderWritten() {
        LoomScript s = new LoomParser(new Lexer(SCRIPT).tokenize()).parseScript();
        RungDef act = s.getLadders().get(0).rung("act");
        assertThat(act.getSlips()).extracting(RungDef.Slip::to).containsExactly("suggest", "watch");
        assertThat(act.getSlipTo()).isEqualTo("suggest");                              // the first, for callers that ask for one
    }

    @Test
    void whenOnlyTheMilderWayDownHoldsTheAgentDropsOneRung() {
        LadderPolicy p = policy(SCRIPT);
        List<V.Rec> h = new ArrayList<>(history(10, 0).cases());
        for (int i = 0; i < 10; i++) h.add(c(0.1 * (i + 1), "reject", "approve"));      // disagreements, but not the dangerous kind
        assertThat(p.slip(2, V.cases(h), NOW)).contains(1);                           // agreement 50%: to suggest
    }

    @Test
    void whenOnlyTheHarsherWayDownHoldsTheAgentDropsFurther() {
        LadderPolicy p = policy(SCRIPT);
        assertThat(p.slip(2, dangerous(60, 2), NOW)).contains(0);                     // 60 of 62 agree (97%), but two dangerous mistakes
    }

    @Test
    void whenBothHoldTheLowestRungWins() {
        LadderPolicy p = policy(SCRIPT);
        List<V.Rec> h = new ArrayList<>(history(0, 10).cases());
        h.add(c(0.2, "approve", "reject"));
        h.add(c(0.1, "approve", "reject"));
        V.Cs both = V.cases(h);
        assertThat(p.slipHit(2, both, NOW).map(LadderPolicy.SlipHit::to)).contains(0);
    }

    @Test
    void whenNeitherHoldsTheAgentStays() {
        assertThat(policy(SCRIPT).slip(2, history(30, 0), NOW)).isEmpty();
    }

    @Test
    void theHitSaysWhichConditionsMadeItHold() {
        LadderPolicy p = policy(SCRIPT);
        LadderPolicy.SlipHit hit = p.slipHit(2, dangerous(60, 2), NOW).orElseThrow();
        assertThat(hit.because()).anyMatch(t -> t.text().contains("count(dangerous)") && t.met().orElse(false));
        assertThat(hit.because()).noneMatch(t -> t.text().contains("agrees"));
    }

    @Test
    void statusListsEachWayDownWithItsOwnTarget() {
        List<LadderPolicy.SlipRule> rules = policy(SCRIPT).slipRules(2, history(30, 0), NOW);
        assertThat(rules).extracting(LadderPolicy.SlipRule::to).containsExactly(1, 0);
        assertThat(rules).allSatisfy(r -> assertThat(r.terms()).isNotEmpty());
        assertThat(policy(SCRIPT).slipRules(0, history(30, 0), NOW)).isEmpty();
    }

    @Test
    void theRulesInForceAndTheirFingerprintCoverEverySlip() {
        LadderPolicy p = policy(SCRIPT);
        assertThat(p.rulesText()).contains("slip to suggest when").contains("slip to watch when");
        String changed = SCRIPT.replace("count(dangerous) >= 2", "count(dangerous) >= 3");
        assertThat(policy(changed).fingerprint()).isNotEqualTo(p.fingerprint());
    }

    @Test
    void aSecondWayDownThatNamesNoRungIsReportedAtLoad() {
        List<String> e = LadderChecksTest.errors(SCRIPT.replace("slip to watch", "slip to wach"));
        assertThat(String.join("\n", e)).contains("no rung of that name").contains("watch");
    }

    @Test
    void aSecondWayDownThatDoesNotGoDownIsReported() {
        assertThat(String.join("\n", LadderChecksTest.errors(SCRIPT.replace("slip to watch", "slip to act")))).contains("must go down");
    }

    @Test
    void aMistypedConditionInASecondWayDownIsReported() {
        assertThat(String.join("\n", LadderChecksTest.errors(SCRIPT.replace("count(dangerous) >= 2", "count(dangerus) >= 2")))).contains("dangerus");
    }

    @Test
    void theLaterWayDownIsDescribedToo() {
        LoomScript script = new LoomParser(new Lexer(SCRIPT).tokenize()).parseScript();
        String text = LadderDescriber.describe(script.getLadders().get(0), script.getDecisions().get(0));
        assertThat(text).contains("slips from act to suggest").contains("slips from act to watch");
    }

    // ---- sampling a confirm rung ----------------------------------------------------------------------------------------------

    @Test
    void aConfirmRungWithASampleSendsThatShareToAPersonBlind() {
        LadderHarness h = new LadderHarness(SCRIPT);
        h.engine.standing("all", "id-a", "m-1");
        h.answeredMany(3, "all", "approve", "approve");
        h.engine.afterResponse("all");
        io.github.llm4j.loom.ladder.LevelLog.Standing s = h.engine.standing("all", "id-a", "m-1");
        assertThat(s.rungName()).isEqualTo("suggest");
        int blind = 0, confirm = 0;
        for (int i = 0; i < 2000; i++) {
            LadderEngine.Plan p = h.engine.plan("all", "run-" + i, s, V.rec(Map.of()), h.engine.history("all", 1), h.clock.millis());
            assertThat(p.rung()).isEqualTo("suggest");
            if (p.mode() == RungDef.Mode.BLIND) {
                blind++;
                assertThat(p.how()).isEqualTo("sample");
            } else {
                confirm++;
                assertThat(p.mode()).isEqualTo(RungDef.Mode.CONFIRM);
            }
        }
        assertThat(blind / 2000.0).isBetween(0.36, 0.44);
        assertThat(blind + confirm).isEqualTo(2000);
    }

    @Test
    void aConfirmRungWithNoSampleIsAlwaysShown() {
        LadderHarness h = new LadderHarness(SCRIPT.replace("sample 40% blind", ""));
        h.engine.standing("all", "id-a", "m-1");
        h.answeredMany(3, "all", "approve", "approve");
        h.engine.afterResponse("all");
        io.github.llm4j.loom.ladder.LevelLog.Standing s = h.engine.standing("all", "id-a", "m-1");
        for (int i = 0; i < 300; i++) {
            assertThat(h.engine.plan("all", "run-" + i, s, V.rec(Map.of()), h.engine.history("all", 1), h.clock.millis()).mode()).isEqualTo(RungDef.Mode.CONFIRM);
        }
    }
}
