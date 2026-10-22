package io.github.llm4j.loom.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.ast.DecisionDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.lexer.Token;
import io.github.llm4j.loom.lexer.TokenType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Ladders, measures and the bindings of a decision inside a whole Loom script. */
@Tag("L2")
class LadderScriptTest {

    static LoomScript parse(String source) {
        return new LoomParser(new Lexer(source).tokenize()).parseScript();
    }

    static final String SCRIPT =
            """
            agent Triager {
                model: "ollama/fake"
                system: "Propose."
                output_schema: { choice: string, reasoning: string }
            }

            // how far a person moved the proposal
            measure edit_size(c) = distance(c.proposal.value,
                                            c.response.final)
            measure agrees(c) = c.response.choice == c.proposal.choice

            ladder Cautious(baseline) {
                rung watch { mode: blind }
                rung suggest {
                    mode: confirm
                    climb when history.count >= 100 and history.median(edit_size) <= 10%
                }
                rung act {
                    mode: autonomous
                    climb when history.count >= 300 and history.mean(views) >= 0.8 * baseline
                    slip to suggest when history.last(50).count(agrees) < 40
                    sample 5% blind
                }
                climbing needs approval from risk-owner
            }

            decision Refund {
                proposed by:   Triager
                choices:       approve, reject, escalate
                group cases by: tier
                remember:      amount, tier
                ask:           support-lead
                ladder:        Cautious(baseline: 1200)
                ceiling:       suggest
            }

            workflow Triage(ticket, tier, amount) {
                decide Refund -> verdict
                note "done {verdict}"
            }
            """;

    @Test
    void aScriptWithMeasuresALadderAndABindingParses() {
        LoomScript s = parse(SCRIPT);
        assertThat(s.getMeasures())
                .extracting(m -> m.getName())
                .containsExactly("edit_size", "agrees");
        assertThat(s.getMeasures().get(0).getLine()).isEqualTo(8);
        assertThat(s.getMeasures().get(0).getBody().show())
                .isEqualTo("distance(c.proposal.value, c.response.final)");
        assertThat(s.getLadders()).hasSize(1);
        assertThat(s.getLadders().get(0).getName()).isEqualTo("Cautious");
        assertThat(s.getLadders().get(0).getLine()).isEqualTo(12);
        assertThat(s.getLadders().get(0).getRungs()).hasSize(3);
        assertThat(s.getLadders().get(0).getParams()).containsExactly("baseline");

        DecisionDef d = s.getDecisions().get(0);
        assertThat(d.getLadder()).isEqualTo("Cautious");
        assertThat(d.getLadderArgs()).containsOnlyKeys("baseline");
        assertThat(d.getLadderArgs().get("baseline").show()).isEqualTo("1200");
        assertThat(d.getCeilingRung()).isEqualTo("suggest");
        assertThat(d.isTrustGiven()).isFalse();
        assertThat(d.getAgent()).isEqualTo("Triager");
        assertThat(s.getWorkflows()).hasSize(1);
    }

    @Test
    void linesAfterADeclarationAreStillNumberedCorrectly() {
        List<Token> tokens = new Lexer(SCRIPT).tokenize();
        Token decision =
                tokens.stream()
                        .filter(
                                t ->
                                        t.getType() == TokenType.IDENTIFIER
                                                && t.getValue().equals("decision"))
                        .findFirst()
                        .orElseThrow();
        assertThat(decision.getLine()).isEqualTo(27);
        Token workflow =
                tokens.stream()
                        .filter(t -> t.getType() == TokenType.WORKFLOW)
                        .findFirst()
                        .orElseThrow();
        assertThat(workflow.getLine()).isEqualTo(37);
        assertThat(parse(SCRIPT).getDecisions().get(0).getLine()).isEqualTo(27);
    }

    @Test
    void bindingArgumentsAreLiteralsOfAnyKind() {
        LoomScript s =
                parse(
                        """
                ladder L(a, b, c, d) { rung x { mode: blind } }
                agent A { model: "m" system: "s" output_schema: { choice: string, reasoning: string } }
                decision D {
                    proposed by: A
                    choices: yes, no
                    ladder: L(a: 14d, b: 90%, c: "gold", d: 2.5)
                }
                """);
        var args = s.getDecisions().get(0).getLadderArgs();
        assert args != null;
        assertThat(args.get("a").show()).isEqualTo("14d");
        assertThat(args.get("b").show()).isEqualTo("90%");
        assertThat(args.get("c").show()).isEqualTo("\"gold\"");
        assertThat(args.get("d").show()).isEqualTo("2.5");
    }

    @Test
    void anArtifactDecisionProposesInsteadOfChoosing() {
        LoomScript s =
                parse(
                        """
                agent W { model: "m" system: "s" output_schema: { body: string, reasoning: string } }
                decision Publish {
                    proposed by: W
                    proposes: post
                    ladder: Blog
                }
                """);
        assertThat(s.getDecisions().get(0).getProposes()).isEqualTo("post");
        assertThat(s.getDecisions().get(0).getChoices()).isEmpty();
    }

    @Test
    void wordsLikeLadderAndMeasureStayUsableAsOrdinaryNamesEverywhereElse() {
        LoomScript s =
                parse(
                        """
                agent ladder { model: "m" system: "s" }
                workflow W(measure, ladder, history) {
                    note "{measure} {ladder} {history}"
                }
                """);
        assertThat(s.getAgents().get(0).getName()).isEqualTo("ladder");
        assertThat(s.getLadders()).isEmpty();
        assertThat(s.getMeasures()).isEmpty();
        assertThat(s.getWorkflows()).hasSize(1);
    }

    @Test
    void aLadderInTheMiddleOfAScriptDoesNotDisturbTheBracesAroundIt() {
        LoomScript s =
                parse(
                        """
                agent A { model: "m" system: "s" }
                ladder L { rung a { mode: blind } }
                agent B { model: "m" system: "s" }
                """);
        assertThat(s.getAgents()).extracting(a -> a.getName()).containsExactly("A", "B");
        assertThat(s.getLadders()).hasSize(1);
    }

    @Test
    void importsBringLaddersAndMeasuresAlong() {
        LoomScript a = parse("ladder L { rung a { mode: blind } }\nmeasure m(c) = true");
        LoomScript b = new LoomScript();
        b.merge(a);
        assertThat(b.getLadders()).hasSize(1);
        assertThat(b.getMeasures()).hasSize(1);
    }

    @Test
    void errorsInsideADeclarationNameTheLineInTheWholeFile() {
        String bad =
                "agent A { model: \"m\" system: \"s\" }\n\nladder L {\n  rung a { mode: blind }\n  rung b { mode: confirm\n    climb when history.count >= }\n}\n";
        assertThatThrownBy(() -> parse(bad))
                .hasMessageContaining("line 6")
                .hasMessageContaining("expected a value");
    }

    @Test
    void aLadderThatNeverClosesIsReported() {
        assertThatThrownBy(() -> parse("ladder L {\n rung a { mode: blind }\n"))
                .hasMessageContaining("never closed")
                .hasMessageContaining("line 1");
    }

    @Test
    void bindingMistakesAreReported() {
        String agent =
                "agent A { model: \"m\" system: \"s\" output_schema: { choice: string, reasoning: string } }\n";
        assertThatThrownBy(
                        () ->
                                parse(
                                        agent
                                                + "decision D { proposed by: A choices: a, b ladder: L(x 1) }"))
                .hasMessageContaining("name: value");
        assertThatThrownBy(
                        () ->
                                parse(
                                        agent
                                                + "decision D { proposed by: A choices: a, b ladder: L(x: ) }"))
                .hasMessageContaining("Expect a value");
        assertThatThrownBy(
                        () -> parse(agent + "decision D { proposed by: A choices: a, b ceiling: }"))
                .hasMessageContaining("name of a rung");
        assertThatThrownBy(
                        () -> parse(agent + "decision D { proposed by: A choices: a, b ladder: }"))
                .hasMessageContaining("name of a ladder");
    }

    @Test
    void rememberedValuesMayDeclareATypeAndAWrongOneIsReported() {
        String agent =
                "agent A { model: \"m\" system: \"s\" output_schema: { choice: string, reasoning: string } }\n";
        DecisionDef d =
                parse(
                                agent
                                        + "decision D { proposed by: A choices: a, b remember: amount: number, tier: text, vip: flag, memo ask: x }")
                        .getDecisions()
                        .get(0);
        assertThat(d.getRemember()).containsExactly("amount", "tier", "vip", "memo");
        assertThat(d.getRememberTypes())
                .containsOnly(
                        Map.entry("amount", "number"),
                        Map.entry("tier", "text"),
                        Map.entry("vip", "flag"));
        assertThatThrownBy(
                        () ->
                                parse(
                                        agent
                                                + "decision D { proposed by: A choices: a, b remember: amount: numbr }"))
                .hasMessageContaining("write number, text or flag");
    }
}
