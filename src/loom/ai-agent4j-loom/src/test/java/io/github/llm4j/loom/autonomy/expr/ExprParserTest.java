package io.github.llm4j.loom.autonomy.expr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("L1")
class ExprParserTest {

    private static String show(String s) {
        return ExprParser.parse(s).show();
    }

    @Test
    void precedenceFollowsTheUsualOrder() {
        assertThat(show("a or b and c")).isEqualTo("(a or (b and c))");
        assertThat(show("not a and b")).isEqualTo("((not a) and b)");
        assertThat(show("1 + 2 * 3")).isEqualTo("(1 + (2 * 3))");
        assertThat(show("a < b + 1")).isEqualTo("(a < (b + 1))");
        assertThat(show("-a * 2")).isEqualTo("((-a) * 2)");
        assertThat(show("(a or b) and c")).isEqualTo("((a or b) and c)");
    }

    @Test
    void comparisonsDoNotChain() {
        assertThatThrownBy(() -> ExprParser.parse("a < b < c"))
                .isInstanceOf(ExprException.class)
                .hasMessageContaining("column 7");
    }

    @Test
    void methodChainsFieldAccessAndFunctionCalls() {
        assertThat(show("history.last(50).where(careful).rate(agrees).lower95 >= 0.9"))
                .isEqualTo("(history.last(50).where(careful).rate(agrees).lower95 >= 0.9)");
        assertThat(show("history.count")).isEqualTo("history.count");
        assertThat(show("history.count()")).isEqualTo("history.count()");
        assertThat(show("edit_size(c) < 0.02")).isEqualTo("(edit_size(c) < 0.02)");
        assertThat(show("distance(c.proposal.value, c.response.final)"))
                .isEqualTo("distance(c.proposal.value, c.response.final)");
        assertThat(show("history.where(amount > 200 and tier == \"gold\").count()"))
                .isEqualTo("history.where(((amount > 200) and (tier == \"gold\"))).count()");
    }

    @Test
    void literals() {
        assertThat(show("90%")).isEqualTo("90%");
        assertThat(show("14d")).isEqualTo("14d");
        assertThat(show("[\"legal\", \"pricing\"]")).isEqualTo("[\"legal\", \"pricing\"]");
        assertThat(show("case_topic in [\"legal\"]")).isEqualTo("(case_topic in [\"legal\"])");
        assertThat(show("true and not false")).isEqualTo("(true and (not false))");
        assertThat(show("[]")).isEqualTo("[]");
    }

    @Test
    void errorsNameTheColumnAndSayWhatWasExpected() {
        assertThatThrownBy(() -> ExprParser.parse("history.count >="))
                .isInstanceOf(ExprException.class)
                .hasMessageContaining("expected a value")
                .hasMessageContaining("column 17");
        assertThatThrownBy(() -> ExprParser.parse("(a and b"))
                .isInstanceOf(ExprException.class)
                .hasMessageContaining("expected ')'");
        assertThatThrownBy(() -> ExprParser.parse("a b"))
                .isInstanceOf(ExprException.class)
                .hasMessageContaining("unexpected 'b'");
        assertThatThrownBy(() -> ExprParser.parse("history."))
                .isInstanceOf(ExprException.class)
                .hasMessageContaining("expected a name after '.'");
        assertThatThrownBy(() -> ExprParser.parse("f(1,)")).isInstanceOf(ExprException.class);
        assertThatThrownBy(() -> ExprParser.parse(""))
                .isInstanceOf(ExprException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void deepNestingIsRejectedNotAStackOverflow() {
        String deep = "(".repeat(500) + "a" + ")".repeat(500);
        assertThatThrownBy(() -> ExprParser.parse(deep))
                .isInstanceOf(ExprException.class)
                .hasMessageContaining("too deeply");
    }

    @Test
    void parsingTheShownFormGivesTheSameShape() {
        for (String s :
                new String[] {
                    "history.last(50).count(dangerous) >= 2 or history.since(1d).count >= 50",
                    "a + b * (c - d) / 2 == 3",
                    "not (x in [1, 2, 3])",
                    "history.where(careful).median(edit_size) <= 10%"
                }) {
            String once = show(s);
            assertThat(show(once)).isEqualTo(once);
        }
    }
}
