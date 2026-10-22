package io.github.llm4j.loom.ladder;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.DecisionDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.autonomy.expr.V;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** What a ladder says about a history: who climbs, who slips, who is asked, who is sampled. */
@Tag("L4")
class LadderPolicyTest {

    static final long DAY = 86_400_000L;
    static final long NOW = 500 * DAY;

    static final String SCRIPT =
            """
            agent T { model: "m" system: "s" output_schema: { choice: string, reasoning: string } }
            measure agrees(c) = c.response.choice == c.proposal.choice
            ladder L(baseline) {
                rung watch { mode: blind }
                rung suggest {
                    mode: confirm
                    climb when history.count >= 10 and history.rate(agrees).lower95 >= 0.6
                    slip when history.last(10).rate(agrees) < 0.3
                }
                rung act {
                    mode: autonomous
                    climb when history.count >= 20 and history.rate(agrees).lower95 >= 0.8 and history.count(dangerous) == 0
                    slip to watch when history.last(10).count(dangerous) >= 2, history.last(20).rate(agrees) < 0.5
                    sample 25% blind
                    escalate when case.amount > baseline or history.since(1d).count >= 5
                }
                climbing is automatic
            }
            decision D {
                proposed by: T
                choices: approve, reject, escalate
                remember: amount
                ask: lead
                dangerous mistake: propose approve, person decides reject
                ladder: L(baseline: 200)
                ceiling: act
            }
            """;

    static LadderPolicy policy() {
        LoomScript s = new LoomParser(new Lexer(SCRIPT).tokenize()).parseScript();
        DecisionDef d = s.getDecisions().get(0);
        return LadderPolicy.of(s, d);
    }

    static V.Rec c(double daysAgo, String proposed, String decided, Object... kv) {
        Map<String, V> m = new LinkedHashMap<>();
        m.put("at", V.num(NOW - daysAgo * DAY));
        m.put("proposal", V.rec(Map.of("choice", V.str(proposed))));
        m.put("response", V.rec(Map.of("choice", V.str(decided))));
        for (int i = 0; i < kv.length; i += 2)
            m.put(
                    (String) kv[i],
                    kv[i + 1] instanceof Number n
                            ? V.num(n.doubleValue())
                            : V.str(String.valueOf(kv[i + 1])));
        return V.rec(m);
    }

    /** n cases in which the agent matched the person, then k in which it did not. */
    static V.Cs history(int agree, int disagree) {
        List<V.Rec> h = new ArrayList<>();
        int total = agree + disagree;
        for (int i = 0; i < total; i++)
            h.add(
                    i < agree
                            ? c(total - i, "approve", "approve")
                            : c(total - i, "approve", "reject"));
        return V.cases(h);
    }

    @Test
    void theRungsAreNamedInOrderAndTheCeilingIsARungIndex() {
        LadderPolicy p = policy();
        assertThat(p.rungs()).containsExactly("watch", "suggest", "act");
        assertThat(p.ceiling()).isEqualTo(2);
        assertThat(p.modeOf(0).word()).isEqualTo("blind");
        assertThat(p.modeOf(2).word()).isEqualTo("autonomous");
        assertThat(p.indexOf("suggest")).isEqualTo(1);
        assertThat(p.indexOf("nowhere")).isEqualTo(-1);
        assertThat(p.automatic()).isTrue();
    }

    @Test
    void climbingNeedsTheWholeConditionAndNeverHoldsOnNoData() {
        LadderPolicy p = policy();
        assertThat(p.climbs(1, history(0, 0), NOW)).isFalse();
        assertThat(p.climbs(1, history(9, 0), NOW)).isFalse(); // not enough cases
        assertThat(p.climbs(1, history(10, 0), NOW)).isTrue(); // 10 of 10: lower bound 0.72
        assertThat(p.climbs(1, history(6, 4), NOW))
                .isFalse(); // enough cases, too many disagreements
        assertThat(p.climbs(2, history(19, 0), NOW)).isFalse();
        assertThat(p.climbs(2, history(20, 0), NOW))
                .isTrue(); // lower bound 0.84, no dangerous cases
        assertThat(p.climbs(0, history(100, 0), NOW)).isFalse(); // the first rung has no climb
    }

    @Test
    void theDangerousMeasureIsGeneratedFromTheDecision() {
        LadderPolicy p = policy();
        List<V.Rec> h = new ArrayList<>(history(30, 0).cases());
        assertThat(p.climbs(2, V.cases(h), NOW)).isTrue();
        h.add(c(0.5, "approve", "reject")); // the agent approved what the person rejected
        assertThat(p.climbs(2, V.cases(h), NOW)).isFalse();
    }

    @Test
    void slippingGoesToTheRungBelowOrTheOneNamedAndOnlyWhenTheConditionHolds() {
        LadderPolicy p = policy();
        assertThat(p.slip(1, history(0, 10), NOW)).contains(0); // default target: the rung below
        assertThat(p.slip(1, history(10, 0), NOW)).isEmpty();
        assertThat(p.slip(1, history(0, 0), NOW)).isEmpty(); // no data, no demotion
        assertThat(p.slip(0, history(0, 50), NOW)).isEmpty(); // the first rung has none
        List<V.Rec> h = new ArrayList<>(history(30, 0).cases());
        h.add(c(0.4, "approve", "reject"));
        assertThat(p.slip(2, V.cases(h), NOW)).isEmpty(); // one dangerous mistake is not two
        h.add(c(0.3, "approve", "reject"));
        assertThat(p.slip(2, V.cases(h), NOW)).contains(0); // 'slip to watch': a comma list is 'or'
        assertThat(p.slip(2, history(5, 15), NOW))
                .contains(0); // second alternative: agreement under 50%
    }

    @Test
    void escalationReadsTheCaseTheLadderParametersAndTheRecentHistory() {
        LadderPolicy p = policy();
        V.Rec big = c(0, "approve", "approve", "amount", 250);
        V.Rec small = c(0, "approve", "approve", "amount", 20);
        assertThat(p.escalates(2, big, history(30, 0), NOW)).isTrue();
        assertThat(p.escalates(2, small, history(30, 0), NOW)).isFalse();
        List<V.Rec> busy = new ArrayList<>();
        for (int i = 0; i < 5; i++) busy.add(c(0.1 * i, "approve", "approve"));
        assertThat(p.escalates(2, small, V.cases(busy), NOW)).isTrue(); // five cases in a day
        assertThat(p.escalates(1, big, history(30, 0), NOW))
                .isFalse(); // the other rungs have no escalate
        assertThat(p.escalates(2, c(0, "a", "b"), V.cases(List.of()), NOW))
                .isFalse(); // an unknown amount does not escalate by itself
    }

    @Test
    void samplingIsDeterministicPerCaseAndMatchesTheShare() {
        LadderPolicy p = policy();
        int hits = 0;
        for (int i = 0; i < 20_000; i++) {
            boolean first = p.sampled(2, "run-" + i);
            assertThat(p.sampled(2, "run-" + i)).isEqualTo(first);
            if (first) hits++;
        }
        assertThat(hits / 20_000.0).isBetween(0.23, 0.27);
        assertThat(p.sampled(1, "run-1")).isFalse(); // only rungs that name a share sample
        assertThat(p.sampled(0, "run-1")).isFalse();
    }

    @Test
    void explainingAClimbShowsEachTermWithWhatIsAndWhatIsNeeded() {
        LadderPolicy p = policy();
        List<LadderPolicy.Term> terms = p.explainClimb(2, history(16, 0), NOW);
        assertThat(terms).hasSize(3);
        assertThat(terms.get(0).text()).isEqualTo("history.count >= 20");
        assertThat(terms.get(0).actual()).isEqualTo("16");
        assertThat(terms.get(0).needed()).isEqualTo("20");
        assertThat(terms.get(0).met()).isEqualTo(Optional.of(false));
        assertThat(terms.get(1).text()).isEqualTo("history.rate(agrees).lower95 >= 0.8");
        assertThat(terms.get(1).met()).isEqualTo(Optional.of(true));
        assertThat(terms.get(1).actual()).startsWith("0.");
        assertThat(terms.get(2).met()).isEqualTo(Optional.of(true));
        List<LadderPolicy.Term> none = p.explainClimb(2, history(0, 0), NOW);
        assertThat(none.get(1).actual()).isEqualTo("unknown");
        assertThat(none.get(1).met()).isEmpty();
    }

    @Test
    void theRulesInForceAreStableTextThatChangesWhenARuleDoes() {
        LadderPolicy a = policy();
        String same = a.rulesText();
        assertThat(policy().rulesText()).isEqualTo(same);
        assertThat(same)
                .contains("rung act")
                .contains("mode: autonomous")
                .contains("sample 25%")
                .contains("climb when history.count >= 20");
        assertThat(a.fingerprint()).isEqualTo(policy().fingerprint()).hasSize(64);
        DecisionDef d2;
        LoomScript s =
                new LoomParser(new Lexer(SCRIPT.replace(">= 20 and", ">= 25 and")).tokenize())
                        .parseScript();
        assertThat(LadderPolicy.of(s, s.getDecisions().get(0)).fingerprint())
                .isNotEqualTo(a.fingerprint());
        LoomScript t =
                new LoomParser(
                                new Lexer(
                                                SCRIPT.replace(
                                                        "measure agrees(c) = c.response.choice == c.proposal.choice",
                                                        "measure agrees(c) = true"))
                                        .tokenize())
                        .parseScript();
        assertThat(LadderPolicy.of(t, t.getDecisions().get(0)).fingerprint())
                .isNotEqualTo(a.fingerprint());
        LoomScript u =
                new LoomParser(
                                new Lexer(SCRIPT.replace("ceiling: act", "ceiling: suggest"))
                                        .tokenize())
                        .parseScript();
        assertThat(LadderPolicy.of(u, u.getDecisions().get(0)).fingerprint())
                .isNotEqualTo(a.fingerprint());
        LoomScript v =
                new LoomParser(
                                new Lexer(SCRIPT.replace("baseline: 200", "baseline: 300"))
                                        .tokenize())
                        .parseScript();
        assertThat(LadderPolicy.of(v, v.getDecisions().get(0)).fingerprint())
                .isNotEqualTo(a.fingerprint());
        LoomScript w =
                new LoomParser(
                                new Lexer(SCRIPT.replace("remember: amount", "remember: amount"))
                                        .tokenize())
                        .parseScript();
        assertThat(LadderPolicy.of(w, w.getDecisions().get(0)).fingerprint())
                .isEqualTo(a.fingerprint());
    }
}
