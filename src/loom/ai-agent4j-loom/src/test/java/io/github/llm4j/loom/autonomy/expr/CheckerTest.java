package io.github.llm4j.loom.autonomy.expr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.autonomy.expr.Checker.T;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** What can be said about a ladder expression before anything runs. */
@Tag("L1")
class CheckerTest {

    private static Checker checker(String... measures) {
        Map<String, Interpreter.Measure> ms = new LinkedHashMap<>();
        for (int i = 0; i < measures.length; i += 2) ms.put(measures[i], new Interpreter.Measure(measures[i], "c", ExprParser.parse(measures[i + 1])));
        Map<String, T> fields = new LinkedHashMap<>();
        fields.put("amount", T.NUM);
        fields.put("tier", T.STR);
        fields.put("topic", T.STR);
        fields.put("shown", T.BOOL);
        fields.put("review_time", T.DUR);
        fields.put("response", T.ANY);
        fields.put("proposal", T.ANY);
        return new Checker(fields, Map.of("baseline", T.NUM), ms, true, true);
    }

    private static T type(String e, String... measures) {
        return checker(measures).type(ExprParser.parse(e));
    }

    private static void rejects(String e, String message, String... measures) {
        assertThatThrownBy(() -> checker(measures).type(ExprParser.parse(e))).isInstanceOf(ExprException.class).hasMessageContaining(message);
    }

    @Test
    void typesOfTheCommonShapes() {
        assertThat(type("history.count >= 100 and history.span >= 14d")).isEqualTo(T.BOOL);
        assertThat(type("history.rate(shown)")).isEqualTo(T.RATIO);
        assertThat(type("history.rate(shown).lower95")).isEqualTo(T.NUM);
        assertThat(type("history.rate(shown) >= 0.9")).isEqualTo(T.BOOL);
        assertThat(type("history.where(amount > 200).count()")).isEqualTo(T.NUM);
        assertThat(type("history.mean(review_time)")).isEqualTo(T.DUR);
        assertThat(type("history.median(amount)")).isEqualTo(T.NUM);
        assertThat(type("history.by(tier).rate(shown).min()")).isEqualTo(T.NUM);
        assertThat(type("history.since(1d).count >= 50")).isEqualTo(T.BOOL);
        assertThat(type("history.last(20).mean(amount) < 0.5 * baseline")).isEqualTo(T.BOOL);
        assertThat(type("case.amount > 200 or case.topic in [\"legal\", \"pricing\"]")).isEqualTo(T.BOOL);
        assertThat(type("history.any(shown)")).isEqualTo(T.BOOL);
    }

    @Test
    void measuresAreTypedByTheirBodiesAndUsableAsPredicatesAndValues() {
        assertThat(type("history.count(big)", "big", "c.amount > 200")).isEqualTo(T.NUM);
        assertThat(type("history.mean(slow)", "slow", "c.review_time")).isEqualTo(T.DUR);
        assertThat(type("history.where(big).rate(small)", "big", "c.amount > 200", "small", "c.amount < 10")).isEqualTo(T.RATIO);
        assertThat(type("history.count(composed)", "composed", "big(c) and not small(c)", "big", "c.amount > 1", "small", "c.amount < 0"))
                .isEqualTo(T.NUM);
    }

    @Test
    void unknownNamesGetASuggestion() {
        rejects("history.count(agrees2)", "unknown name 'agrees2'", "agrees", "c.shown");
        rejects("history.count(agrees2)", "did you mean 'agrees'?", "agrees", "c.shown");
        rejects("amout > 3", "unknown name 'amout'");
        rejects("history.where(amout > 3).count", "did you mean 'amount'?");
        rejects("histroy.count", "did you mean 'history'?");
    }

    @Test
    void unknownMethodsAndFieldsGetASuggestionAndTheirPlace() {
        rejects("history.ratee(shown)", "no method 'ratee'");
        rejects("history.ratee(shown)", "did you mean 'rate'?");
        rejects("history.rate(shown).lower", "did you mean 'lower95'?");
        rejects("history.rate(shown).lower95.foo", "a number has no field 'foo'");
        rejects("history.count.where(1)", "a number has no method 'where'");
    }

    @Test
    void methodArgumentsAreChecked() {
        rejects("history.last(\"x\")", "'last' expects a number");
        rejects("history.since(5)", "'since' expects a duration");
        rejects("history.where()", "'where' expects 1 argument");
        rejects("history.quantile(amount)", "'quantile' expects 2 arguments");
        rejects("history.quantile(amount, \"x\")", "'quantile' expects a number between 0 and 1");
        rejects("history.rate(amount)", "'rate' expects a yes/no condition");
        rejects("history.mean(tier)", "'mean' expects a number or a duration");
        rejects("history.count(1, 2)", "'count' expects 0 or 1 argument");
    }

    @Test
    void operandsMustMakeSense() {
        rejects("history.span >= 5", "cannot compare a duration with a number");
        rejects("case.tier > 3", "cannot compare a text with a number");
        rejects("history.count + \"a\"", "cannot add a number and a text");
        rejects("true < false", "cannot order yes/no values");
        rejects("history.count and true", "'and' needs yes/no values but the left side is a number");
        rejects("not history.count", "'not' needs a yes/no value");
        rejects("3 in 4", "'in' needs a list on the right");
        assertThat(type("case.response.kind == \"accept\"")).isEqualTo(T.BOOL);                 // unknown parts are allowed to be anything
    }

    @Test
    void aConditionMustBeYesOrNo() {
        Checker c = checker();
        assertThat(c.condition(ExprParser.parse("history.count >= 3"))).isEqualTo(T.BOOL);
        assertThatThrownBy(() -> c.condition(ExprParser.parse("history.count"))).isInstanceOf(ExprException.class)
                .hasMessageContaining("is not a yes/no condition").hasMessageContaining("number");
        assertThat(c.condition(ExprParser.parse("case.response.kind"))).isEqualTo(T.ANY);
    }

    @Test
    void measuresCannotCallThemselvesDirectlyOrInACycle() {
        rejects("history.count(a)", "measure 'a' calls itself", "a", "a(c)");
        rejects("history.count(a)", "a → b → a", "a", "b(c)", "b", "a(c)");
    }

    @Test
    void measuresCannotReadHistoryOrTheCurrentCase() {
        rejects("history.count(a)", "measures cannot read 'history'", "a", "history.count > 1");
        rejects("history.count(a)", "measures cannot read 'case'", "a", "case.amount > 1");
    }

    @Test
    void bareFieldNamesWorkOnlyInsideAPerCaseArgument() {
        rejects("amount > 3", "unknown name 'amount'", "x", "true");
        rejects("amount > 3", "use it inside where(...)", "x", "true");
    }
}
