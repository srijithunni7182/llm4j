package io.github.llm4j.loom.autonomy;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.ScriptValidator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** What a script says wrong about a decision is reported at load, naming the line (spec loom-earned-autonomy R1.3, R1.4). */
class DecisionChecksTest {

    static List<ScriptValidator.Problem> check(String source) {
        return new ScriptValidator().validate(DecisionParseTest.parse(source), new ScriptValidator.Context().registeredTools(Set.of("Slack", "Mail", "Calc")));
    }

    static List<String> errors(String source) {
        return check(source).stream().filter(p -> p.severity() == ScriptValidator.Severity.ERROR).map(ScriptValidator.Problem::toString).toList();
    }

    static List<String> warnings(String source) {
        return check(source).stream().filter(p -> p.severity() == ScriptValidator.Severity.WARNING).map(ScriptValidator.Problem::toString).toList();
    }

    private static final String WORKFLOW = "workflow W(tier, amount) { decide Refund -> v }\n";

    /** The example declaration with one phrase replaced. */
    private static String refund(String from, String to) {
        assertThat(Scripts.REFUND).contains(from);
        return Scripts.REFUND.replace(from, to);
    }

    @Test
    @Tag("EA-V1.1")
    void theExampleDeclarationLoadsWithNoProblemsExceptTheToolItTells() {
        assertThat(errors(Scripts.REFUND)).containsExactly("line 6: decision Refund: tell Slack when trust changes: tool Slack is not declared (tool Slack { use: … })");
        assertThat(errors(refund("tell Slack when trust changes", "") + "tool Slack { use: webhook url: \"http://localhost:1/x\" }")).isEmpty();
    }

    @Test
    @Tag("EA-V1.2")
    void theDeclarationIsCheckedAndEveryProblemNamesTheLine() {
        assertThat(errors("decision D { proposed by: Ghost choices: a, b ask: x }")).anyMatch(e -> e.contains("line 1") && e.contains("agent Ghost is not defined"));
        assertThat(errors(Scripts.AGENT + "decision D { proposed by: Triager choices: a ask: x }")).anyMatch(e -> e.contains("at least two choices"));
        assertThat(errors(Scripts.AGENT + "decision D { proposed by: Triager choices: a, a ask: x }")).anyMatch(e -> e.contains("listed twice"));
        assertThat(errors(Scripts.AGENT + "decision D { proposed by: Triager choices: a, b }")).anyMatch(e -> e.contains("say who decides"));
        assertThat(errors(Scripts.AGENT + "decision D { choices: a, b ask: x }")).anyMatch(e -> e.contains("say which agent proposes"));
        assertThat(errors(Scripts.AGENT + "decision D { proposed by: Triager choices: a, b ask: x dangerous mistake: propose z, person decides a }"))
                .anyMatch(e -> e.contains("z is not one of the choices"));
        assertThat(errors(Scripts.AGENT + "decision D { proposed by: Triager choices: a, b ask: x }\ndecision D { proposed by: Triager choices: a, b ask: x }")).anyMatch(e -> e.contains("already declared"));
        assertThat(errors("workflow W() { decide Missing -> v }")).anyMatch(e -> e.contains("line 1") && e.contains("no decision named Missing"));
    }

    @Test
    @Tag("EA-V1.2")
    void theLaddersNumbersAndLevelsAreCheckedToo() {
        String head = Scripts.AGENT + "decision D { proposed by: Triager choices: a, b, escalate ask: x ";
        assertThat(errors(head + "trust { to suggest: after 0 cases, agreeing at least 90%  moving up is automatic } }")).anyMatch(e -> e.contains("after N cases needs N of at least 1"));
        assertThat(errors(head + "trust { to suggest: after 10 cases, agreeing at least 150%  moving up is automatic } }")).anyMatch(e -> e.contains("from 0% to 100%, not 150.0%"));
        assertThat(errors(head + "trust { never go above act to act: after 10 cases, agreeing at least 90%  moving up is automatic } }")).anyMatch(e -> e.contains("would skip a level"));
        assertThat(errors(head + "trust { to watch: after 10 cases, agreeing at least 90%  moving up is automatic } }")).anyMatch(e -> e.contains("nothing is below watch"));
        assertThat(errors(head + "trust { start at suggest never go above watch } }")).anyMatch(e -> e.contains("never go above watch is below start at suggest"));
        assertThat(errors(head + "trust { to suggest: after 10 cases, agreeing at least 90% } }")).anyMatch(e -> e.contains("say who approves moving up"));
        assertThat(errors(head + "trust { drop to act when 2 reversals in 50 cases } }")).anyMatch(e -> e.contains("not dropping"));
        assertThat(errors(head + "trust { drop to suggest when 9 reversals in 3 cases } }")).anyMatch(e -> e.contains("can never happen"));
        assertThat(errors(head + "trust { drop to suggest when agreement falls below 120% } }")).anyMatch(e -> e.contains("from 0% to 100%"));
        assertThat(errors(head + "trust { drop to suggest when 2 dangerous mistakes in 50 cases } }")).anyMatch(e -> e.contains("names none"));
        assertThat(errors(head + "trust { check 150% of cases with a person who doesn't see the proposal } }")).anyMatch(e -> e.contains("from 0% to 100%"));
    }

    @Test
    @Tag("EA-V1.2")
    void whatTheCaseRemembersMustBeSetBeforeTheDecide() {
        String source = Scripts.AGENT + "decision D { proposed by: Triager choices: a, b ask: x group cases by: tier remember: amount, review.score }\n";
        assertThat(errors(source + "workflow W(tier) { decide D -> v }")).anyMatch(e -> e.contains("line 7") && e.contains("remember: amount is not a variable set before this point"));
        assertThat(errors(source + "workflow W(amount) { decide D -> v }")).anyMatch(e -> e.contains("group cases by: tier is not a variable"));
        assertThat(errors(source + "workflow W(tier, amount) { delegate \"x\" to Triager -> review\n decide D -> v }")).isEmpty();
        assertThat(errors(source + "workflow W(tier, amount) { decide D -> v\n delegate \"x\" to Triager -> review }")).anyMatch(e -> e.contains("review.score"));
        assertThat(errors(source + "workflow W(tier, amount, items) { for each item in items { delegate \"x\" to Triager -> review\n decide D -> v } }")).isEmpty();
        assertThat(errors(source + "workflow W(tier, amount) { checkpoint c starting with review = \"x\"\n decide D -> v }")).isEmpty();
    }

    @Test
    @Tag("EA-V1.3")
    void theAgentThatProposesMustBeAbleToProduceAChoiceAndAReasoning() {
        String decision = "decision D { proposed by: A choices: a, b ask: x }\n";
        assertThat(errors("agent A { model: \"m\" system: \"s\" }\n" + decision)).anyMatch(e -> e.contains("can't produce a proposal") && e.contains("output_schema"));
        assertThat(errors("agent A { model: \"m\" system: \"s\" output_schema: { choice: string } }\n" + decision)).anyMatch(e -> e.contains("needs a reasoning field"));
        assertThat(errors("agent A { model: \"m\" system: \"s\" output_schema: { reasoning: string } }\n" + decision)).anyMatch(e -> e.contains("needs a choice field"));
        assertThat(errors("agent A { model: \"m\" system: \"s\" output_schema: { choice: string, reasoning: string, confidence: string } }\n" + decision)).anyMatch(e -> e.contains("confidence field"));
        assertThat(errors("agent A { model: \"m\" system: \"s\" output_schema: { choice: enum[\"a\", \"zzz\"], reasoning: string } }\n" + decision)).anyMatch(e -> e.contains("allows zzz"));
        assertThat(errors("agent A { model: \"m\" system: \"s\" output_schema: { choice: enum[\"a\", \"b\"], reasoning: string, confidence: number } }\n" + decision)).isEmpty();
        assertThat(errors("agent A { model: \"m\" system: \"s\" output_schema: { choice: string, reasoning: string, confidence: number } }\n" + decision)).isEmpty();
    }

    @Test
    @Tag("EA-V1.9")
    void aDecisionThatMayReachActAndIsFollowedByAnUnapprovedEffectIsReported() {
        String script = Scripts.AGENT + """
                tool Mail { use: webhook url: "http://localhost:1/x" }
                agent Payer { model: "m" system: "s" tools: [Mail] }
                decision Refund { proposed by: Triager choices: approve, reject, escalate ask: x
                    trust { never go above act to suggest: after 5 cases, agreeing at least 50% to act: after 5 cases, agreeing at least 50% moving up is automatic } }
                workflow W() {
                    decide Refund -> verdict
                    alt (verdict == "approve") { delegate "pay" to Payer -> done }
                }
                """;
        assertThat(warnings(script)).anyMatch(w -> w.contains("without approval") && w.contains("Mail (agent Payer)") && w.contains("line 11"));
        assertThat(warnings(script.replace("tools: [Mail]", "tools: [Mail] approve: [Mail]"))).noneMatch(w -> w.contains("without approval"));
        assertThat(warnings(script.replace("use: webhook", "use: webhook unattended: true"))).noneMatch(w -> w.contains("without approval"));
        assertThat(warnings(script.replace("never go above act", "never go above suggest"))).noneMatch(w -> w.contains("without approval"));
    }

    @Test
    @Tag("EA-V6.4")
    void keepingTheTrustWithAHighCeilingWarnsAndAnAliasModelWarns() {
        String trust = "trust { never go above act to suggest: after 5 cases, agreeing at least 50% to act: after 5 cases, agreeing at least 50% moving up is automatic }";
        String script = Scripts.AGENT + "decision D { proposed by: Triager choices: a, b, escalate ask: x when the agent changes: keep the trust " + trust + " }";
        assertThat(warnings(script)).anyMatch(w -> w.contains("keep the trust with never go above act"));
        assertThat(warnings(script.replace("never go above act", "never go above suggest"))).noneMatch(w -> w.contains("keep the trust"));
        assertThat(warnings(script.replace("model: \"m\"", "model: \"gemini-latest\""))).anyMatch(w -> w.contains("model alias gemini-latest"));
    }

    @Test
    @Tag("EA-V6.5")
    void aNoticeIsGivenWhenThereIsNoEscalateChoiceOrNoRuleToClimbTo() {
        assertThat(warnings(Scripts.AGENT + "decision D { proposed by: Triager choices: a, b ask: x }")).anyMatch(w -> w.contains("no choice called escalate"));
        assertThat(warnings(Scripts.AGENT + "decision D { proposed by: Triager choices: a, b, escalate ask: x trust { never go above act to suggest: after 5 cases, agreeing at least 50% moving up is automatic } }"))
                .anyMatch(w -> w.contains("no rule for moving up to act"));
    }
}
