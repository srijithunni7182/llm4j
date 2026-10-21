package io.github.llm4j.loom.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.ast.LadderDef;
import io.github.llm4j.loom.ast.MeasureDef;
import io.github.llm4j.loom.ast.RungDef;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The text of a {@code ladder} or {@code measure} block into its parts. */
@Tag("L2")
class LadderParserTest {

    static final String REFUND =
            """
            ladder RefundLadder {
                rung watch   { mode: blind }

                rung suggest {
                    mode: confirm
                    climb when history.count >= 100 and history.span >= 14d
                           and history.rate(agrees).lower95 >= 0.90        // cautious
                }

                rung act {
                    mode: autonomous
                    climb when history.count >= 300 and history.rate(agrees).lower95 >= 97%
                    slip to suggest when history.last(50).count(dangerous) >= 2,
                                         history.last(100).count(reversed) >= 2,
                                         history.rate(agrees) < 0.92
                    sample 5% blind
                    escalate when case.amount > 200, history.since(1d).count >= 50
                }

                climbing needs approval from risk-owner
            }
            """;

    @Test
    void aWholeLadderParsesIntoRungsModesAndConditions() {
        LadderDef l = LadderParser.parseLadder(REFUND, 10);
        assertThat(l.getName()).isEqualTo("RefundLadder");
        assertThat(l.getLine()).isEqualTo(10);
        assertThat(l.getParams()).isEmpty();
        assertThat(l.getRungs())
                .extracting(RungDef::getName)
                .containsExactly("watch", "suggest", "act");
        assertThat(l.getRungs())
                .extracting(RungDef::getMode)
                .containsExactly(RungDef.Mode.BLIND, RungDef.Mode.CONFIRM, RungDef.Mode.AUTONOMOUS);
        assertThat(l.getApprover()).isEqualTo("risk-owner");
        assertThat(l.isAutomatic()).isFalse();

        RungDef watch = l.rung("watch");
        assertThat(watch.getClimb()).isNull();
        assertThat(watch.getLine()).isEqualTo(11);

        RungDef suggest = l.rung("suggest");
        assertThat(suggest.getClimb().show())
                .isEqualTo(
                        "(((history.count >= 100) and (history.span >= 14d)) and (history.rate(agrees).lower95 >= 0.9))");
        assertThat(suggest.getClimbLine()).isEqualTo(15);

        RungDef act = l.rung("act");
        assertThat(act.getSlipTo()).isEqualTo("suggest");
        assertThat(act.getSlip().show())
                .isEqualTo(
                        "(((history.last(50).count(dangerous) >= 2) or (history.last(100).count(reversed) >= 2)) or (history.rate(agrees) < 0.92))");
        assertThat(act.getSamplePercent()).isEqualTo(0.05);
        assertThat(act.getEscalate().show())
                .isEqualTo("((case.amount > 200) or (history.since(1d).count >= 50))");
        assertThat(act.getClimb().show()).contains("97%");
    }

    @Test
    void parametersAutomaticClimbingAndDefaultSlip() {
        LadderDef l =
                LadderParser.parseLadder(
                        """
                ladder BlogLadder(baseline_views, floor) {
                    rung edit { mode: confirm }
                    rung publish {
                        mode: autonomous
                        climb when history.mean(views) >= 0.8 * baseline_views
                        slip when history.last(10).count(takedown) >= 1
                    }
                    climbing is automatic
                }
                """,
                        1);
        assertThat(l.getParams()).containsExactly("baseline_views", "floor");
        assertThat(l.isAutomatic()).isTrue();
        assertThat(l.getApprover()).isNull();
        assertThat(l.rung("publish").getSlipTo()).isNull();
        assertThat(l.rung("publish").getSlip()).isNotNull();
    }

    @Test
    void methodChainsMayContinueOnTheNextLine() {
        LadderDef l =
                LadderParser.parseLadder(
                        """
                ladder L {
                    rung a { mode: blind }
                    rung b {
                        mode: confirm
                        climb when history.where(careful)
                                          .rate(agrees)
                                          .lower95 >= 0.9
                        slip when history.count > 1 or
                                  history.span > 3d
                    }
                }
                """,
                        1);
        assertThat(l.rung("b").getClimb().show())
                .isEqualTo("(history.where(careful).rate(agrees).lower95 >= 0.9)");
        assertThat(l.rung("b").getSlip().show())
                .isEqualTo("((history.count > 1) or (history.span > 3d))");
    }

    @Test
    void aSingleLineLadderAndCommentsInsideStringsAreKept() {
        LadderDef l =
                LadderParser.parseLadder(
                        "ladder S { rung a { mode: blind } rung b { mode: confirm climb when history.count >= 3 and case.topic == \"a//b\" } }",
                        4);
        assertThat(l.getRungs()).hasSize(2);
        assertThat(l.rung("b").getClimb().show()).contains("\"a//b\"");
    }

    @Test
    void rungNamesMayBeAnyWordAndHyphenated() {
        LadderDef l =
                LadderParser.parseLadder(
                        "ladder X { rung shadow-run { mode: blind } rung go-live { mode: autonomous climb when history.count > 1 } }",
                        1);
        assertThat(l.getRungs())
                .extracting(RungDef::getName)
                .containsExactly("shadow-run", "go-live");
    }

    @Test
    void measuresAreFunctionsOfOneCase() {
        MeasureDef m =
                LadderParser.parseMeasure(
                        "measure edit_size(c) = distance(c.proposal.value, c.response.final)", 7);
        assertThat(m.getName()).isEqualTo("edit_size");
        assertThat(m.getParam()).isEqualTo("c");
        assertThat(m.getBody().show()).isEqualTo("distance(c.proposal.value, c.response.final)");
        assertThat(m.getText()).isEqualTo("distance(c.proposal.value, c.response.final)");
        assertThat(m.getLine()).isEqualTo(7);

        MeasureDef two =
                LadderParser.parseMeasure(
                        "measure untouched(x) = accepted(x)\n    or edit_size(x) < 0.02   // tiny",
                        1);
        assertThat(two.getParam()).isEqualTo("x");
        assertThat(two.getBody().show()).isEqualTo("(accepted(x) or (edit_size(x) < 0.02))");
    }

    // ---------- errors ----------

    private static void rejects(String text, String message) {
        assertThatThrownBy(() -> LadderParser.parseLadder(text, 1))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining(message);
    }

    @Test
    void mistakesAreReportedWithTheLineAndWhatWasExpected() {
        rejects(
                "ladder L {\n  rung a { mode: blind }\n  rung b { mode: confirm\n    cimb when history.count > 1 }\n}",
                "line 4");
        rejects(
                "ladder L {\n  rung a { mode: blind }\n  rung b { mode: confirm\n    cimb when history.count > 1 }\n}",
                "Unexpected \"cimb\"");
        rejects(
                "ladder L { rung a { mode: blind } rung b { mode: confirm climb history.count > 1 } }",
                "Write: climb when");
        rejects(
                "ladder L { rung a { mode: sneaky } }",
                "mode must be blind, confirm or autonomous (not \"sneaky\")");
        rejects("ladder L { rung a { } }", "A rung needs a mode");
        rejects("ladder L { rung a { mode: blind  mode: confirm } }", "mode is given twice");
        rejects(
                "ladder L { rung a { mode: blind } rung b { mode: confirm climb when history.count >>= 1 } }",
                "line 1");
        rejects(
                "ladder L { rung a { mode: blind } rung b { mode: confirm climb when history.count >= } }",
                "expected a value");
        rejects(
                "ladder L { rung a { mode: blind } rung b { mode: confirm climb when } }",
                "Write a condition after \"climb when\"");
        rejects("ladder L { rung a { mode: blind sample blind } }", "Write: sample 5% blind");
        rejects(
                "ladder L { rung a { mode: blind } climbing sometimes }",
                "climbing needs approval from NAME");
        rejects("ladder L { rung a { mode: blind } ", "Expect '}' to close ladder L");
        rejects("ladder { }", "Expect a ladder name");
        rejects("ladder L(a b) { }", "Expect ',' or ')'");
        rejects(
                "ladder L { rung a { mode: blind } rung a { mode: confirm climb when true } }",
                "rung a is declared twice");
    }

    @Test
    void theErrorOfAnExpressionNamesWhereInTheRuleItIs() {
        assertThatThrownBy(
                        () ->
                                LadderParser.parseLadder(
                                        "ladder L {\n rung a { mode: blind }\n rung b { mode: confirm\n  climb when history.count >= 3 and (x\n }\n}",
                                        20))
                .hasMessageContaining("line 23")
                .hasMessageContaining("expected ')'");
    }

    @Test
    void measureMistakes() {
        assertThatThrownBy(() -> LadderParser.parseMeasure("measure m c = 1", 3))
                .hasMessageContaining("line 3")
                .hasMessageContaining("Write: measure name(c) = expression");
        assertThatThrownBy(() -> LadderParser.parseMeasure("measure m(c) = ", 3))
                .hasMessageContaining("Write an expression after \"=\"");
        assertThatThrownBy(() -> LadderParser.parseMeasure("measure m(c, d) = 1", 3))
                .hasMessageContaining("takes exactly one case");
        assertThatThrownBy(() -> LadderParser.parseMeasure("measure m(c) = c.a +", 3))
                .hasMessageContaining("expected a value");
    }
}
