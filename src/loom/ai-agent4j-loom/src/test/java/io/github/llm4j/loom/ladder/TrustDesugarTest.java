package io.github.llm4j.loom.ladder;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.DecisionDef;
import io.github.llm4j.loom.ast.LadderDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.RungDef;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** A trust block written as a ladder: its shape, and what it declines to write. */
@Tag("L7")
class TrustDesugarTest {

    static String decision(String extraLines, String trust) {
        return "agent T { model: \"m\" system: \"s\" output_schema: { choice: string, reasoning: string } }\n"
                + "decision D { proposed by: T choices: approve, reject, escalate remember: amount, tier group cases by: tier "
                + "dangerous mistake: propose approve, person decides reject ask: lead " + extraLines + "\ntrust {\n" + trust.replace("  ", "\n") + "\n} }\n";
    }

    static final String FULL = "never go above act  to suggest: after 100 cases over 14 days, agreeing at least 90%  "
            + "to act: after 300 cases over 30 days, agreeing at least 97%, with no dangerous mistakes  judge on the latest 200 cases  "
            + "check 5% of cases with a person who doesn't see the proposal  always ask a person when amount > 200  "
            + "drop to suggest when 2 dangerous mistakes in 50 cases  drop to watch when agreement falls below 60%  moving up needs approval from: risk-owner";

    static TrustDesugar.Result of(String source) {
        LoomScript s = LadderChecksTest.parse(source);
        return TrustDesugar.of(s.getDecisions().get(0), Set.of());
    }

    @Test
    void aTrustBlockBecomesThreeRungsWithTheModesOfTheLevels() {
        TrustDesugar.Result r = of(decision("", FULL));
        assertThat(r.supported()).isTrue();
        LadderDef l = r.ladder();
        assertThat(l.getName()).isEqualTo("trust:D");
        assertThat(l.isSynthetic()).isTrue();
        assertThat(l.getRungs()).extracting(RungDef::getName).containsExactly("watch", "suggest", "act");
        assertThat(l.getRungs()).extracting(RungDef::getMode).containsExactly(RungDef.Mode.BLIND, RungDef.Mode.CONFIRM, RungDef.Mode.AUTONOMOUS);
        assertThat(l.getApprover()).isEqualTo("risk-owner");
        assertThat(l.isAutomatic()).isFalse();
    }

    @Test
    void eachStepUpIsTheRuleItWasWrittenAs() {
        LadderDef l = of(decision("", FULL)).ladder();
        assertThat(l.rung("suggest").getClimbText()).isEqualTo(
                "history.where(trust_blind).count >= 100 and history.where(trust_blind).between(36500d, 14d).count >= 1 "
                        + "and history.where(trust_blind).last(200).rate(trust_agrees).lower95 >= 90%");
        assertThat(l.rung("act").getClimbText()).contains(">= 300").contains("30d").contains("lower95 >= 97%").contains("count(dangerous) == 0");
        assertThat(l.rung("watch").getClimb()).isNull();
    }

    @Test
    void aStepWithNoRuleCanNeverBeEarned() {
        LadderDef l = of(decision("", "never go above act  to suggest: after 10 cases, agreeing at least 80%  moving up is automatic")).ladder();
        assertThat(l.rung("act").getClimbText()).isEqualTo("false");
    }

    @Test
    void eachDropRuleBecomesASlipToItsOwnRungOnTheRungsItAppliesTo() {
        LadderDef l = of(decision("", FULL)).ladder();
        assertThat(l.rung("suggest").getSlips()).extracting(RungDef.Slip::to).containsExactly("watch");           // a drop to suggest does not apply at suggest
        assertThat(l.rung("act").getSlips()).extracting(RungDef.Slip::to).containsExactly("watch", "suggest");    // lowest target first
        assertThat(l.rung("act").getSlips().get(1).text()).isEqualTo("history.where(trust_blind).last(50).count(dangerous) >= 2");
        assertThat(l.rung("suggest").getSlips().get(0).text()).contains("count >= 20").contains("rate(trust_agrees) < 60%");
    }

    @Test
    void reversalsCountOnlyWhatTheAgentDecidedAndUnusableProposalsCountEveryProposal() {
        LadderDef l = of(decision("", "to suggest: after 10 cases, agreeing at least 80%  to act: after 20 cases, agreeing at least 90%  "
                + "drop to suggest when 2 reversals in 30 cases  drop to watch when 3 unusable proposals in 40 cases  moving up is automatic")).ladder();
        assertThat(l.rung("act").getSlips().get(1).text()).isEqualTo("history.where(trust_by_agent).last(30).count(trust_reversed) >= 2");
        assertThat(l.rung("act").getSlips().get(0).text()).isEqualTo("history.last(40).count(trust_malformed) >= 3");
    }

    @Test
    void theAuditSharesAndTheAskConditionsLandOnTheRungsWhereTheyWereApplied() {
        LadderDef l = of(decision("", FULL)).ladder();
        assertThat(l.rung("suggest").getSamplePercent()).isEqualTo(0.05);
        assertThat(l.rung("act").getSamplePercent()).isEqualTo(0.05);
        assertThat(l.rung("watch").getSamplePercent()).isZero();
        assertThat(l.rung("act").getEscalateText()).isEqualTo("case.amount > 200");
    }

    @Test
    void anAskConditionCanCompareToAWordAndSeveralAreAlternatives() {
        LadderDef l = of(decision("", "to suggest: after 10 cases, agreeing at least 80%  always ask a person when tier == gold  always ask a person when amount >= 50.5  moving up is automatic")).ladder();
        assertThat(l.rung("act").getEscalateText()).isEqualTo("case.tier == \"gold\" or case.amount >= 50.5");
    }

    @Test
    void whatTheLanguageCannotSayInOneLineStaysWithTheOriginalEngineAndSaysWhy() {
        assertThat(of(decision("", "start at suggest  moving up is automatic")).unsupported()).contains("starts at suggest");
        assertThat(of(decision("when the agent changes: test it on past cases", "moving up is automatic")).unsupported()).contains("past cases");
        assertThat(of(decision("", "always ask a person after 50 cases a day  moving up is automatic")).unsupported()).contains("calendar days");
        assertThat(of(decision("", "always ask a person when region == west  moving up is automatic")).unsupported()).contains("remembered value");
        assertThat(of(decision("", "always ask a person when urgent  moving up is automatic")).unsupported()).contains("remembered value");
    }

    @Test
    void aScriptMeasureWithTheSameNameStopsTheDesugaring() {
        LoomScript s = LadderChecksTest.parse(decision("", "moving up is automatic"));
        assertThat(TrustDesugar.of(s.getDecisions().get(0), Set.of("trust_agrees")).unsupported()).contains("trust_agrees");
    }

    @Test
    void aDangerousRuleWithNoDangerousMistakesDeclaredHasNothingToCount() {
        String src = "agent T { model: \"m\" system: \"s\" output_schema: { choice: string, reasoning: string } }\n"
                + "decision D { proposed by: T choices: a, b ask: lead trust {\n to suggest: after 10 cases, agreeing at least 80%, with no dangerous mistakes\n"
                + "to act: after 20 cases, agreeing at least 90%\n drop to suggest when 2 dangerous mistakes in 50 cases\n moving up is automatic } }\n";
        LadderDef l = of(src).ladder();
        assertThat(l.rung("suggest").getClimbText()).doesNotContain("dangerous");
        assertThat(l.rung("act").getSlips()).isEmpty();
    }

    @Test
    void applyingItGivesTheDecisionTheLadderTheCeilingAndTheMeasures() {
        LoomScript s = LadderChecksTest.parse(decision("", "never go above suggest  to suggest: after 10 cases, agreeing at least 80%  moving up is automatic"));
        s.applyTrustLadders();
        DecisionDef d = s.getDecisions().get(0);
        assertThat(d.getLadder()).isEqualTo("trust:D");
        assertThat(d.isLadderFromTrust()).isTrue();
        assertThat(d.getCeilingRung()).isEqualTo("suggest");
        assertThat(d.getTrustMeasures()).extracting(m -> m.getName()).contains("trust_blind", "trust_agrees", "trust_by_agent", "trust_malformed", "trust_reversed");
        assertThat(s.getLadders()).extracting(LadderDef::getName).contains("trust:D");
        s.applyTrustLadders();
        assertThat(s.getLadders().stream().filter(x -> x.getName().equals("trust:D"))).hasSize(1);
        assertThat(List.copyOf(s.getDecisions())).hasSize(1);
    }

    @Test
    void aDecisionThatAlreadyHasALadderIsLeftAlone() {
        String src = "measure agrees(c) = c.response.choice == c.proposal.choice\n"
                + "ladder L { rung watch { mode: blind } rung act { mode: autonomous climb when history.count >= 5 } climbing is automatic }\n"
                + "agent T { model: \"m\" system: \"s\" output_schema: { choice: string, reasoning: string } }\n"
                + "decision D { proposed by: T choices: a, b ask: lead ladder: L }\n";
        LoomScript s = LadderChecksTest.parse(src);
        s.applyTrustLadders();
        assertThat(s.getDecisions().get(0).getLadder()).isEqualTo("L");
        assertThat(s.getDecisions().get(0).isLadderFromTrust()).isFalse();
    }
}
