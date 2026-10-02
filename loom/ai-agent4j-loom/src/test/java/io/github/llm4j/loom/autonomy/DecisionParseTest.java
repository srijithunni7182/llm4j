package io.github.llm4j.loom.autonomy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.ast.DecideStmt;
import io.github.llm4j.loom.ast.DecisionDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The decision declaration reads as plain phrases and parses into its parts (spec loom-earned-autonomy R1). */
class DecisionParseTest {

    static LoomScript parse(String source) {
        return new LoomParser(new Lexer(source).tokenize()).parseScript();
    }

    @Test
    @Tag("EA-V1.1")
    void theExampleDeclarationParsesIntoEveryOneOfItsParts() {
        LoomScript script = parse(Scripts.REFUND);
        DecisionDef d = script.getDecisions().get(0);

        assertThat(d.getName()).isEqualTo("Refund");
        assertThat(d.getAgent()).isEqualTo("Triager");
        assertThat(d.getChoices()).containsExactly("approve", "reject", "escalate");
        assertThat(d.getGroupBy()).isEqualTo("tier");
        assertThat(d.getRemember()).containsExactly("amount", "reason", "customer_since");
        assertThat(d.getDangerous()).containsExactly(new DecisionDef.Mistake("approve", "reject"));
        assertThat(d.getAsk()).isEqualTo("support-lead");
        assertThat(d.getKeepDays()).isEqualTo(180);
        assertThat(d.getOnChange()).isEqualTo(DecisionDef.OnChange.TEST_ON_PAST);
        assertThat(d.getTellTool()).isEqualTo("Slack");

        assertThat(d.getStartAt()).isEqualTo(Level.WATCH);
        assertThat(d.getCeiling()).isEqualTo(Level.SUGGEST);
        assertThat(d.getUpRules().get(Level.SUGGEST)).isEqualTo(new DecisionDef.UpRule(Level.SUGGEST, 100, 14, 90, false, null, 21));
        DecisionDef.UpRule act = d.getUpRules().get(Level.ACT);
        assertThat(act.cases()).isEqualTo(300);
        assertThat(act.days()).isEqualTo(30);
        assertThat(act.agreeingAtLeast()).isEqualTo(97);
        assertThat(act.noDangerous()).isTrue();
        assertThat(d.getWindow()).isEqualTo(300);
        assertThat(d.getAuditPercent()).isEqualTo(5);
        assertThat(d.getAskWhen()).containsExactly("amount > 200");
        assertThat(d.getAskAfterPerDay()).isEqualTo(50);
        assertThat(d.getDropRules()).extracting(DecisionDef.DropRule::count).containsExactly(
                DecisionDef.Count.DANGEROUS_MISTAKES, DecisionDef.Count.REVERSALS, DecisionDef.Count.AGREEMENT_BELOW, DecisionDef.Count.UNUSABLE_PROPOSALS);
        assertThat(d.getDropRules().get(0).n()).isEqualTo(2);
        assertThat(d.getDropRules().get(0).inCases()).isEqualTo(50);
        assertThat(d.getDropRules().get(2).percent()).isEqualTo(92);
        assertThat(d.getApprover()).isEqualTo("risk-owner");
        assertThat(d.isAutomatic()).isFalse();

        assertThat(script.getWorkflows().get(0).getStatements().get(0)).isInstanceOfSatisfying(DecideStmt.class, s -> {
            assertThat(s.getDecision()).isEqualTo("Refund");
            assertThat(s.getVariable()).isEqualTo("verdict");
        });
    }

    @Test
    @Tag("EA-V1.1")
    void theOtherPhrasesParseToo() {
        DecisionDef d = parse(Scripts.AGENT + """
                decision D {
                    proposed by: Triager
                    choices: yes, no
                    when the agent changes: keep the trust
                    task: "Decide for {amount}"
                    flag cases with no verdict after 3 days
                    trust {
                        to act: after 50 cases, agreeing at least 95%, with at most 1% dangerous mistakes
                        moving up is automatic
                    }
                }
                """).getDecisions().get(0);
        assertThat(d.getOnChange()).isEqualTo(DecisionDef.OnChange.KEEP_TRUST);
        assertThat(d.getTask()).isEqualTo("Decide for {amount}");
        assertThat(d.getStaleDays()).isEqualTo(3);
        assertThat(d.getUpRules().get(Level.ACT).dangerousAtMost()).isEqualTo(1.0);
        assertThat(d.getUpRules().get(Level.ACT).days()).isZero();
        assertThat(d.isAutomatic()).isTrue();
        assertThat(parse(Scripts.AGENT + "decision D { proposed by: Triager choices: a, b when the agent changes: start over }").getDecisions().get(0).getOnChange())
                .isEqualTo(DecisionDef.OnChange.START_OVER);
    }

    @Test
    @Tag("EA-V1.8")
    void aPhraseOutOfOrderIsReportedInTheAuthorsOwnWords() {
        assertThatThrownBy(() -> parse("decision D { proposed A }")).hasMessageContaining("Write: proposed by: AgentName");
        assertThatThrownBy(() -> parse("decision D { trust { to act: 100 cases } }")).hasMessageContaining("needs \"after N cases\"");
        assertThatThrownBy(() -> parse("decision D { trust { to act: after 100 cases, agreeing at least 90 } }")).hasMessageContaining("percentage");
        assertThatThrownBy(() -> parse("decision D { trust { to act: after 100 cases } }")).hasMessageContaining("Write: to act");
        assertThatThrownBy(() -> parse("decision D { trust { start at flying } }")).hasMessageContaining("watch, suggest or act");
        assertThatThrownBy(() -> parse("decision D { trust { drop to suggest when 2 mice in 50 cases } }")).hasMessageContaining("reversals");
        assertThatThrownBy(() -> parse("decision D { trust { moving up sideways } }")).hasMessageContaining("moving up needs approval");
        assertThatThrownBy(() -> parse("decision D { frobnicate: 1 }")).hasMessageContaining("A decision holds");
        assertThatThrownBy(() -> parse("workflow W() { decide Refund verdict }")).hasMessageContaining("decide Refund -> verdict");
        assertThatThrownBy(() -> parse("decision D { trust { always ask a person } }")).hasMessageContaining("always ask a person when");
        assertThatThrownBy(() -> parse("decision D { trust { to suggest: after 1.5 cases, agreeing at least 90% } }")).hasMessageContaining("whole number");
    }

    @Test
    @Tag("EA-V1.8")
    void aDecisionInsideAWorkflowIsAnError() {
        assertThatThrownBy(() -> parse("workflow W() { decision D { choices: a, b } }")).hasMessageContaining("Expected statement");
    }
}
