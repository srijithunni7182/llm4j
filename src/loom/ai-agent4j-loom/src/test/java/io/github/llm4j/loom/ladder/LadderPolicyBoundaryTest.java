package io.github.llm4j.loom.ladder;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.autonomy.expr.V;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The edges of a policy: rungs out of range, exact shares, the terms of every kind of comparison,
 * and what changes the rules' fingerprint.
 */
@Tag("L4")
class LadderPolicyBoundaryTest {

    static final String BASE = LadderPolicyTest.SCRIPT;

    static LadderPolicy of(String script) {
        LoomScript s = new LoomParser(new Lexer(script).tokenize()).parseScript();
        return LadderPolicy.of(s, s.getDecisions().get(0));
    }

    @Test
    void rungsOutOfRangeNeverClimbSlipEscalateOrSample() {
        LadderPolicy p = of(BASE);
        V.Cs h = LadderPolicyTest.history(30, 0);
        V.Rec c = LadderPolicyTest.c(0, "approve", "approve", "amount", 999);
        long now = LadderPolicyTest.NOW;
        assertThat(p.climbs(3, h, now)).isFalse();
        assertThat(p.climbs(-1, h, now)).isFalse();
        assertThat(p.slip(3, h, now)).isEmpty();
        assertThat(p.slip(-1, h, now)).isEmpty();
        assertThat(p.escalates(3, c, h, now)).isFalse();
        assertThat(p.escalates(-1, c, h, now)).isFalse();
        assertThat(p.sampled(3, "x")).isFalse();
        assertThat(p.sampled(-1, "x")).isFalse();
        assertThat(p.explainClimb(0, h, now)).isEmpty();
        assertThat(p.explainClimb(3, h, now)).isEmpty();
        assertThat(p.explainSlip(0, h, now)).isEmpty();
        assertThat(p.explainSlip(3, h, now)).isEmpty();
        assertThat(p.explainClimb(1, h, now)).isNotEmpty();
        assertThat(p.explainSlip(1, h, now)).isNotEmpty();
        assertThat(p.escalates(2, c, h, now)).isTrue();
    }

    @Test
    void theRungsKnowTheirModesNamesAndTheFirstAutonomousOne() {
        LadderPolicy p = of(BASE);
        assertThat(p.ladderName()).isEqualTo("L");
        assertThat(p.decisionName()).isEqualTo("D");
        assertThat(p.nameOf(1)).isEqualTo("suggest");
        assertThat(p.firstAutonomous()).isEqualTo(2);
        assertThat(of(BASE.replace("mode: autonomous", "mode: confirm")).firstAutonomous())
                .isEqualTo(-1);
        assertThat(p.approver()).isNull();
        assertThat(
                        of(BASE.replace(
                                        "climbing is automatic",
                                        "climbing needs approval from boss"))
                                .approver())
                .isEqualTo("boss");
    }

    @Test
    void theCeilingIsTheNamedRungOrTheTopOne() {
        assertThat(of(BASE).ceiling()).isEqualTo(2);
        assertThat(of(BASE.replace("ceiling: act", "ceiling: watch")).ceiling()).isZero();
        assertThat(of(BASE.replace("ceiling: act", "ceiling: suggest")).ceiling()).isEqualTo(1);
        assertThat(of(BASE.replace("ceiling: act", "")).ceiling())
                .as("no ceiling: the top rung")
                .isEqualTo(2);
    }

    @Test
    void aSlipToANamedRungGoesThereAndOneThatIsNotBelowFallsBackToTheRungBelow() {
        LadderPolicy p = of(BASE);
        assertThat(p.slip(2, LadderPolicyTest.history(5, 15), LadderPolicyTest.NOW)).contains(0);
        LadderPolicy plain = of(BASE.replace("slip to watch when", "slip when"));
        assertThat(plain.slip(2, LadderPolicyTest.history(5, 15), LadderPolicyTest.NOW))
                .contains(1);
    }

    @Test
    void aShareOfOneHundredPercentSamplesEverythingAndZeroNothing() {
        LadderPolicy all = of(BASE.replace("sample 25% blind", "sample 100% blind"));
        LadderPolicy none = of(BASE.replace("sample 25% blind", ""));
        for (int i = 0; i < 500; i++) {
            assertThat(all.sampled(2, "c" + i)).isTrue();
            assertThat(none.sampled(2, "c" + i)).isFalse();
        }
        LadderPolicy one = of(BASE.replace("sample 25% blind", "sample 1% blind"));
        int hits = 0;
        for (int i = 0; i < 50_000; i++) if (one.sampled(2, "case-" + i)) hits++;
        assertThat(hits / 50_000.0).isBetween(0.005, 0.015);
        // the same case on another decision or rung is a different draw
        LadderPolicy renamed = of(BASE.replace("decision D {", "decision E {"));
        int differ = 0;
        for (int i = 0; i < 2000; i++)
            if (renamed.sampled(2, "x" + i) != of(BASE).sampled(2, "x" + i)) differ++;
        assertThat(differ).isGreaterThan(100);
    }

    @Test
    void everyKindOfComparisonIsExplainedWithWhatIsAndWhatIsNeeded() {
        for (String op : List.of("==", "!=", "<", "<=", ">", ">=")) {
            LadderPolicy p = of(BASE.replace("history.count >= 20", "history.count " + op + " 20"));
            LadderPolicy.Term t =
                    p.explainClimb(2, LadderPolicyTest.history(20, 0), LadderPolicyTest.NOW).get(0);
            assertThat(t.text()).isEqualTo("history.count " + op + " 20");
            assertThat(t.actual()).isEqualTo("20");
            assertThat(t.needed()).isEqualTo("20");
            assertThat(t.met()).isPresent();
        }
        LadderPolicy plain = of(BASE.replace("history.count >= 20 and", "true and"));
        LadderPolicy.Term t =
                plain.explainClimb(2, LadderPolicyTest.history(20, 0), LadderPolicyTest.NOW).get(0);
        assertThat(t.needed()).isEmpty();
        assertThat(t.actual()).isEqualTo("true");
    }

    @Test
    void theRulesTextListsEverythingAndTheFingerprintChangesWithEachOfIt() {
        LadderPolicy p = of(BASE);
        String text = p.rulesText();
        assertThat(text)
                .contains("ladder L for decision D")
                .contains("baseline = 200")
                .contains("rung watch")
                .contains("mode: blind")
                .contains(
                        "slip to watch when (history.last(10).count(dangerous) >= 2) or (history.last(20).rate(agrees) < 0.5)")
                .contains("sample 25% blind")
                .contains("escalate when case.amount > baseline or history.since(1d).count >= 5")
                .contains("climbing is automatic")
                .contains("ceiling: act")
                .contains("measure agrees(c) = c.response.choice == c.proposal.choice");
        assertThat(
                        of(BASE.replace(
                                        "climbing is automatic",
                                        "climbing needs approval from boss"))
                                .rulesText())
                .contains("climbing needs approval from boss");
        String f = p.fingerprint();
        for (String[] edit :
                new String[][] {
                    {"sample 25% blind", "sample 30% blind"},
                    {"slip to watch when", "slip to suggest when"},
                    {"history.since(1d).count >= 5", "history.since(1d).count >= 6"},
                    {"mode: confirm", "mode: blind"},
                    {"climbing is automatic", "climbing needs approval from boss"},
                    {
                        "slip when history.last(10).rate(agrees) < 0.3",
                        "slip when history.last(10).rate(agrees) < 0.2"
                    }
                }) {
            if (!BASE.contains(edit[0])) continue;
            assertThat(of(BASE.replace(edit[0], edit[1])).fingerprint())
                    .as(edit[0])
                    .isNotEqualTo(f);
        }
        assertThat(of(BASE.replace("decision D {", "decision E {")).fingerprint()).isNotEqualTo(f);
    }

    @Test
    void theDangerousMeasureOnlyExistsWhenTheDecisionNamesAMistake() {
        String without =
                BASE.replace("dangerous mistake: propose approve, person decides reject", "");
        LoomScript script = new LoomParser(new Lexer(without).tokenize()).parseScript();
        var errors =
                new io.github.llm4j.loom.execution.ScriptValidator()
                                .validate(
                                        script,
                                        new io.github.llm4j.loom.execution.ScriptValidator.Context()
                                                .registeredTools(java.util.Set.of()))
                                .stream()
                                .filter(
                                        p ->
                                                p.severity()
                                                        == io.github.llm4j.loom.execution
                                                                .ScriptValidator.Severity.ERROR)
                                .toList();
        assertThat(errors)
                .as("the load-time check is what stops an unknown measure")
                .anyMatch(e -> e.toString().contains("unknown name 'dangerous'"));
    }
}
