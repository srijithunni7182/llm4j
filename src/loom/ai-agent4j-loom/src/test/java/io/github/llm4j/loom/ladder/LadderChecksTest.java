package io.github.llm4j.loom.ladder;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.ScriptValidator;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** What a script says wrong about a ladder, a measure or a binding is reported at load, naming the line. */
@Tag("L2")
class LadderChecksTest {

    static final String AGENT = "agent Triager { model: \"ollama/fake\" system: \"s\" output_schema: { choice: string, reasoning: string } }\n";
    static final String WRITER = "agent Writer { model: \"ollama/fake\" system: \"s\" output_schema: { body: string, reasoning: string } }\n";

    static LoomScript parse(String s) {
        return new LoomParser(new Lexer(s).tokenize()).parseScript();
    }

    static List<ScriptValidator.Problem> check(String source) {
        return new ScriptValidator().validate(parse(source), new ScriptValidator.Context().registeredTools(Set.of("Slack")));
    }

    static List<String> errors(String source) {
        return check(source).stream().filter(p -> p.severity() == ScriptValidator.Severity.ERROR).map(ScriptValidator.Problem::toString).toList();
    }

    static List<String> warnings(String source) {
        return check(source).stream().filter(p -> p.severity() == ScriptValidator.Severity.WARNING).map(ScriptValidator.Problem::toString).toList();
    }

    static final String GOOD_LADDER = """
            measure agrees(c) = c.response.choice == c.proposal.choice
            measure dangerous_one(c) = c.proposal.choice == "approve" and c.response.choice == "reject"
            ladder Cautious {
                rung watch { mode: blind }
                rung suggest {
                    mode: confirm
                    climb when history.count >= 100 and history.rate(agrees).lower95 >= 0.9
                }
                rung act {
                    mode: autonomous
                    climb when history.count >= 300 and history.count(dangerous_one) == 0
                    slip to suggest when history.last(50).count(dangerous_one) >= 2
                    sample 5% blind
                    escalate when case.amount > 200
                }
                climbing needs approval from risk-owner
            }
            """;

    static final String GOOD_DECISION = """
            decision Refund {
                proposed by: Triager
                choices: approve, reject, escalate
                remember: amount, tier
                ask: lead
                ladder: Cautious
                ceiling: suggest
                when the agent changes: start over
            }
            workflow W(amount, tier) { decide Refund -> v note "{v}" }
            """;

    static String good(String from, String to) {
        String all = AGENT + GOOD_LADDER + GOOD_DECISION;
        assertThat(all).contains(from);
        return all.replace(from, to);
    }

    @Test
    void aWellFormedLadderAndBindingLoadWithNoProblems() {
        assertThat(errors(AGENT + GOOD_LADDER + GOOD_DECISION)).isEmpty();
        assertThat(warnings(AGENT + GOOD_LADDER + GOOD_DECISION)).isEmpty();
    }

    @Test
    void ladderShapeIsChecked() {
        assertThat(errors(AGENT + "ladder L { }")).anyMatch(e -> e.contains("ladder L") && e.contains("needs at least one rung"));
        assertThat(errors(good("rung watch { mode: blind }", "rung watch { mode: blind climb when true }")))
                .anyMatch(e -> e.contains("line 5") && e.contains("first rung") && e.contains("climb"));
        assertThat(errors(good("rung watch { mode: blind }", "rung watch { mode: blind slip when true }")))
                .anyMatch(e -> e.contains("first rung") && e.contains("slip"));
        assertThat(errors(good("climb when history.count >= 100 and history.rate(agrees).lower95 >= 0.9", "")))
                .anyMatch(e -> e.contains("rung suggest") && e.contains("can never be reached") && e.contains("climb when"));
        assertThat(errors(good("slip to suggest when", "slip to nowhere when"))).anyMatch(e -> e.contains("slip to nowhere") && e.contains("no rung of that name"));
        assertThat(errors(good("slip to suggest when", "slip to act when"))).anyMatch(e -> e.contains("slip to act") && e.contains("must go down"));
        assertThat(errors(good("slip to suggest when", "slip to sugest when"))).anyMatch(e -> e.contains("did you mean 'suggest'?"));
        assertThat(errors(good("climbing needs approval from risk-owner", ""))).anyMatch(e -> e.contains("say who approves climbing"));
        assertThat(errors(good("climbing needs approval from risk-owner", "climbing is automatic"))).isEmpty();
    }

    @Test
    void duplicateNamesAndClashesAreReported() {
        assertThat(errors(GOOD_LADDER + GOOD_LADDER)).anyMatch(e -> e.contains("ladder Cautious is already declared"));
        assertThat(errors("measure m(c) = true\nmeasure m(c) = false")).anyMatch(e -> e.contains("measure m is already declared"));
        assertThat(errors("measure distance(c) = true")).anyMatch(e -> e.contains("distance is a built-in function"));
        assertThat(errors(good("remember: amount, tier", "remember: amount, tier, agrees"))).anyMatch(e -> e.contains("agrees") && e.contains("same name"));
        assertThat(errors("measure response(c) = true")).anyMatch(e -> e.contains("response") && e.contains("same name as a field of the case"));
    }

    @Test
    void conditionsAreCheckedAgainstTheCaseAndTheMeasures() {
        assertThat(errors(good("history.rate(agrees).lower95 >= 0.9", "history.rate(agres).lower95 >= 0.9")))
                .anyMatch(e -> e.contains("line 8") && e.contains("unknown name 'agres'") && e.contains("did you mean 'agrees'?"));
        assertThat(errors(good("history.count >= 300", "history.count >= \"many\""))).anyMatch(e -> e.contains("cannot compare a number with a text"));
        assertThat(errors(good("history.count >= 100 and", "history.count and"))).anyMatch(e -> e.contains("needs yes/no values"));
        assertThat(errors(good("climb when history.count >= 100 and history.rate(agrees).lower95 >= 0.9", "climb when history.count + 1")))
                .anyMatch(e -> e.contains("is not a yes/no condition"));
        assertThat(errors(good("case.amount > 200", "case.amout > 200"))).anyMatch(e -> e.contains("a case has no field 'amout'") && e.contains("did you mean 'amount'?"));
        assertThat(errors(good("measure agrees(c) = c.response.choice == c.proposal.choice", "measure agrees(c) = c.respnse.choice == c.proposal.choice")))
                .anyMatch(e -> e.contains("measure agrees") && e.contains("no field 'respnse'") && e.contains("did you mean 'response'?"));
    }

    @Test
    void measuresCannotCallThemselvesOrReadTheHistory() {
        assertThat(errors("measure a(c) = b(c)\nmeasure b(c) = a(c)")).anyMatch(e -> e.contains("calls itself") && e.contains("a → b → a"));
        assertThat(errors("measure a(c) = history.count > 1")).anyMatch(e -> e.contains("measures cannot read 'history'"));
    }

    @Test
    void sampleAndEscalateMakeSenseOnlyWhereTheyDoSomething() {
        assertThat(errors(good("sample 5% blind", "sample 150% blind"))).anyMatch(e -> e.contains("sample") && e.contains("between 0% and 100%"));
        assertThat(warnings(good("rung watch { mode: blind }", "rung watch { mode: blind sample 5% blind }")))
                .anyMatch(e -> e.contains("sample") && e.contains("only does something on an autonomous rung"));
        assertThat(warnings(good("rung watch { mode: blind }", "rung watch { mode: blind escalate when true }")))
                .anyMatch(e -> e.contains("escalate") && e.contains("already asks a person"));
        assertThat(warnings(good("    sample 5% blind\n", ""))).anyMatch(e -> e.contains("rung act") && e.contains("no sample"));
        assertThat(warnings(good("    slip to suggest when history.last(50).count(dangerous_one) >= 2\n", ""))).anyMatch(e -> e.contains("rung act") && e.contains("no slip"));
    }

    // ---------- bindings ----------

    @Test
    void aBindingMustNameALadderAndARungOfIt() {
        assertThat(errors(good("ladder: Cautious", "ladder: Cautius"))).anyMatch(e -> e.contains("decision Refund") && e.contains("no ladder named Cautius") && e.contains("did you mean 'Cautious'?"));
        assertThat(errors(good("ceiling: suggest", "ceiling: sugest"))).anyMatch(e -> e.contains("ceiling") && e.contains("no rung named sugest") && e.contains("did you mean 'suggest'?"));
        assertThat(warnings(good("    ceiling: suggest\n", ""))).anyMatch(e -> e.contains("no ceiling") && e.contains("stays on watch"));
        assertThat(errors(good("ceiling: suggest", "ceiling: act"))).isEmpty();
    }

    @Test
    void parametersMustMatchBetweenLadderAndBinding() {
        String ladder = GOOD_LADDER.replace("ladder Cautious {", "ladder Cautious(baseline) {").replace("case.amount > 200", "history.count > baseline");
        String base = AGENT + ladder + GOOD_DECISION;
        assertThat(errors(base)).anyMatch(e -> e.contains("decision Refund") && e.contains("baseline") && e.contains("needs a value"));
        assertThat(errors(base.replace("ladder: Cautious", "ladder: Cautious(baseline: 200)"))).isEmpty();
        assertThat(errors(base.replace("ladder: Cautious", "ladder: Cautious(baseline: 200, extra: 1)"))).anyMatch(e -> e.contains("extra") && e.contains("not a parameter"));
        assertThat(errors(base.replace("ladder: Cautious", "ladder: Cautious(baselin: 200)"))).anyMatch(e -> e.contains("baselin") && e.contains("did you mean 'baseline'?"));
        assertThat(errors(base.replace("ladder: Cautious", "ladder: Cautious(baseline: \"high\")"))).anyMatch(e -> e.contains("cannot compare a number with a text"));
    }

    @Test
    void aDecisionHasItsRulesInOnePlace() {
        String withTrust = good("ceiling: suggest", "ceiling: suggest\n trust { never go above act moving up is automatic }");
        assertThat(errors(withTrust)).anyMatch(e -> e.contains("decision Refund") && e.contains("trust") && e.contains("ladder"));
    }

    @Test
    void aDecisionChoosesOrProposesButNotBoth() {
        assertThat(errors(good("choices: approve, reject, escalate", "choices: approve, reject, escalate\n proposes: reply"))).anyMatch(e -> e.contains("choices") && e.contains("proposes"));
        String artifact = WRITER + GOOD_LADDER + """
                decision Publish {
                    proposed by: Writer
                    proposes: post
                    ask: editors
                    ladder: Cautious
                    ceiling: act
                    when the agent changes: start over
                }
                workflow W2(amount) { decide Publish -> post note "{post}" }
                """;
        assertThat(errors(artifact)).noneMatch(e -> e.contains("at least two choices"));
        assertThat(errors(artifact.replace("proposed by: Writer", "proposed by: Writer\n dangerous mistake: propose a, person decides b"))).anyMatch(e -> e.contains("dangerous mistake") && e.contains("choices"));
        assertThat(errors(artifact.replace("output_schema: { body: string, reasoning: string }", ""))).anyMatch(e -> e.contains("output_schema"));
    }

    @Test
    void changingTheAgentIsStatedAndKeepingTrustIsRefusedForAnAgentThatCanAct() {
        assertThat(warnings(good("    when the agent changes: start over\n", ""))).anyMatch(e -> e.contains("decision Refund") && e.contains("when the agent changes") && e.contains("start over"));
        assertThat(errors(good("ceiling: suggest", "ceiling: act").replace("start over", "keep the trust")))
                .anyMatch(e -> e.contains("keep the trust") && e.contains("autonomous"));
        assertThat(errors(good("start over", "keep the trust"))).isEmpty();
    }

    @Test
    void anUnusedLadderIsAWarningNotAnError() {
        String unused = GOOD_LADDER + AGENT;
        assertThat(errors(unused)).isEmpty();
        assertThat(warnings(unused)).anyMatch(e -> e.contains("ladder Cautious") && e.contains("no decision uses it"));
    }
}
