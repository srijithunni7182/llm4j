package io.github.llm4j.loom.autonomy.expr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.autonomy.expr.Checker.T;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The shapes the first tests did not walk: groups and lists, negation, built-in functions and their arities, and what a script that skips the checks would do. */
@Tag("L1")
class ExprMoreTest {

    static final long DAY = 86_400_000L;

    private static Checker checker() {
        Map<String, T> fields = new LinkedHashMap<>();
        fields.put("amount", T.NUM);
        fields.put("tier", T.STR);
        fields.put("ok", T.BOOL);
        fields.put("t", T.DUR);
        Map<String, Interpreter.Measure> ms = new LinkedHashMap<>();
        ms.put("big", new Interpreter.Measure("big", "c", ExprParser.parse("c.amount > 5")));
        return new Checker(fields, Map.of("limit", T.NUM, "window", T.DUR), ms, true, true);
    }

    private static T type(String e) {
        return checker().type(ExprParser.parse(e));
    }

    private static void rejects(String e, String message) {
        assertThatThrownBy(() -> checker().type(ExprParser.parse(e))).isInstanceOf(ExprException.class).hasMessageContaining(message);
    }

    // ---------- the checker ----------

    @Test
    void negationAndArithmeticKeepTheirKinds() {
        assertThat(type("-case.amount")).isEqualTo(T.NUM);
        assertThat(type("history.mean(t)")).isEqualTo(T.DUR);
        assertThat(type("-history.mean(t)")).isEqualTo(T.DUR);
        assertThat(type("2 * history.mean(t)")).isEqualTo(T.DUR);
        assertThat(type("history.mean(t) / 2")).isEqualTo(T.DUR);
        assertThat(type("history.mean(t) + history.mean(t)")).isEqualTo(T.DUR);
        assertThat(type("history.mean(t) - 1h")).isEqualTo(T.DUR);
        assertThat(type("limit * 2 + 1")).isEqualTo(T.NUM);
        assertThat(type("history.rate(ok) * 100")).isEqualTo(T.NUM);
        assertThat(type("window > 1d")).isEqualTo(T.BOOL);
        rejects("-case.tier", "cannot negate a text");
        rejects("history.mean(t) * history.mean(t)", "cannot multiply a duration and a duration");
        rejects("1 - case.tier", "cannot subtract a number and a text");
        rejects("limit / case.tier", "cannot divide a number and a text");
        rejects("case.tier * 2", "cannot multiply");
    }

    @Test
    void groupsAndListsHaveTheirOwnMethods() {
        assertThat(type("history.by(tier).count().min()")).isEqualTo(T.NUM);
        assertThat(type("history.by(tier).rate(ok).max()")).isEqualTo(T.NUM);
        assertThat(type("history.by(tier).mean(amount).sum()")).isEqualTo(T.NUM);
        assertThat(type("history.by(tier).count().count")).isEqualTo(T.NUM);
        assertThat(type("history.by(tier).count().count()")).isEqualTo(T.NUM);
        assertThat(type("history.by(tier).count")).isEqualTo(T.NUM);
        assertThat(type("history.by(tier).any(ok)")).isEqualTo(T.LIST);
        assertThat(type("history.by(tier).quantile(amount, 0.5)")).isEqualTo(T.LIST);
        assertThat(type("history.by(tier).median(t)")).isEqualTo(T.LIST);
        rejects("history.by(tier).where(ok)", "has no method 'where'");
        rejects("history.by(tier).count().where(ok)", "has no method 'where'");
        rejects("history.by(tier).count().min(1)", "'min' expects 0 arguments");
        rejects("history.by(tier).nothing", "has no field 'nothing'");
        rejects("history.by(tier).count().nothing", "has no field 'nothing'");
    }

    @Test
    void windowsAndOtherMethodsCheckTheirArguments() {
        assertThat(type("history.between(30d, 7d).count")).isEqualTo(T.NUM);
        assertThat(type("history.since(window).count")).isEqualTo(T.NUM);
        assertThat(type("history.last(limit).count")).isEqualTo(T.NUM);
        assertThat(type("history.quantile(t, 0.9)")).isEqualTo(T.DUR);
        assertThat(type("history.all(ok)")).isEqualTo(T.BOOL);
        assertThat(type("history.sum(amount)")).isEqualTo(T.NUM);
        rejects("history.between(30d)", "'between' expects 2 arguments");
        rejects("history.between(30, 7d)", "'between' expects a duration");
        rejects("history.between(30d, 7)", "'between' expects a duration");
        rejects("history.by()", "'by' expects 1 argument");
        rejects("history.any()", "'any' expects 1 argument");
        rejects("history.rate(ok, ok)", "'rate' expects 1 argument");
        rejects("history.count(ok, ok)", "'count' expects 0 or 1 argument");
    }

    @Test
    void functionsAndMeasuresAreCalledWithTheRightNumberAndKindOfArguments() {
        assertThat(type("distance(case.amount, case.amount) < 1")).isEqualTo(T.BOOL);
        assertThat(type("abs(-3) > 1")).isEqualTo(T.BOOL);
        rejects("distance(1) < 1", "'distance' expects 2 arguments");
        rejects("abs(1, 2) > 1", "'abs' expects 1 argument");
        rejects("absolute(1) > 1", "unknown function 'absolute'");
        rejects("dist(1, 2) > 1", "did you mean 'distance'?");
        rejects("big(case, case) == true", "takes one case but got 2 arguments");
        rejects("big(3) == true", "takes a case but got a number");
        assertThat(type("big(case)")).isEqualTo(T.BOOL);
    }

    @Test
    void namesAndFieldsInEveryScope() {
        assertThat(type("case.amount > 1")).isEqualTo(T.BOOL);
        assertThat(type("history.where(big and ok).count")).isEqualTo(T.NUM);
        assertThat(type("history.where(not big).count")).isEqualTo(T.NUM);
        assertThat(type("history.count in [1, 2]")).isEqualTo(T.BOOL);
        assertThat(type("case.tier in []")).isEqualTo(T.BOOL);
        rejects("big", "is a measure of one case");
        rejects("history.where(nothing).count", "unknown name 'nothing'");
        rejects("case.nothing", "a case has no field 'nothing'");
        rejects("history.count.nothing", "has no field 'nothing'");
    }

    @Test
    void aCheckerWithoutHistoryOrCaseRefusesToReadThem() {
        Checker bare = new Checker(Map.of("amount", T.NUM), Map.of(), Map.of(), false, false);
        assertThatThrownBy(() -> bare.type(ExprParser.parse("history.count"))).hasMessageContaining("unknown name 'history'");
        assertThatThrownBy(() -> bare.type(ExprParser.parse("case.amount"))).hasMessageContaining("unknown name 'case'");
        assertThat(bare.measureType("nothing")).as("an unknown measure could be anything").isEqualTo(T.ANY);
    }

    // ---------- the interpreter ----------

    private static V.Rec c(double daysAgo, Object... kv) {
        Map<String, V> m = new LinkedHashMap<>();
        m.put("at", V.num(100 * DAY - daysAgo * DAY));
        for (int i = 0; i < kv.length; i += 2) {
            Object v = kv[i + 1];
            m.put((String) kv[i], v instanceof V x ? x : v instanceof Boolean b ? V.bool(b) : v instanceof Number n ? V.num(n.doubleValue()) : V.str(String.valueOf(v)));
        }
        return V.rec(m);
    }

    private static V eval(String e, List<V.Rec> h) {
        return Interpreter.eval(ExprParser.parse(e), new Interpreter.Context(Map.of("history", V.cases(h)), Map.of(), 100 * DAY));
    }

    @Test
    void groupsReduceToListsThatCanBeReadAgain() {
        List<V.Rec> h = List.of(c(5, "tier", "a", "ok", true, "v", 1), c(4, "tier", "a", "ok", false, "v", 3), c(3, "tier", "b", "ok", true, "v", 10), c(2, "tier", 7, "v", 20));
        assertThat(eval("history.by(tier).any(ok).count", h)).as("one yes/no per group").isEqualTo(V.num(3));
        assertThat(eval("history.by(tier).all(ok).count", h)).as("a group with no yes/no answers has no all()").isEqualTo(V.num(2));
        assertThat(eval("history.by(tier).sum(v).sum()", h)).isEqualTo(V.num(34));
        assertThat(eval("history.by(tier).mean(v).max()", h)).isEqualTo(V.num(20));
        assertThat(eval("history.by(tier).median(v).min()", h)).isEqualTo(V.num(2));
        assertThat(eval("history.by(tier).quantile(v, 1).sum()", h)).isEqualTo(V.num(33));
        assertThat(eval("history.by(tier).count().mean()", h)).isEqualTo(V.num(4.0 / 3));
        assertThat(eval("history.by(tier).count", h)).as("three groups: a, b and the number 7").isEqualTo(V.num(3));
        assertThat(eval("history.by(tier).count().count()", h)).isEqualTo(V.num(3));
        assertThat(eval("history.by(tier).count().min()", List.of())).isEqualTo(V.UNDEFINED);
        assertThat(eval("history.by(tier).count().max()", List.of())).isEqualTo(V.UNDEFINED);
        assertThat(eval("history.by(tier).count().mean()", List.of())).isEqualTo(V.UNDEFINED);
        assertThat(eval("history.by(tier).count().sum()", List.of())).isEqualTo(V.num(0));
        assertThat(eval("history.by(tier).nothing()", h)).isEqualTo(V.UNDEFINED);
        assertThat(eval("history.by(tier).count().nothing()", h)).isEqualTo(V.UNDEFINED);
        assertThat(eval("history.by(ok).count", h)).as("true and false are groups; a case without ok is in none").isEqualTo(V.num(2));
    }

    @Test
    void negationAbsAndMembershipOfUnusualValues() {
        List<V.Rec> h = List.of();
        assertThat(eval("-(1h)", h)).isEqualTo(V.dur(-3_600_000L));
        assertThat(eval("-\"x\"", h)).isEqualTo(V.UNDEFINED);
        assertThat(eval("-nothing", h)).isEqualTo(V.UNDEFINED);
        assertThat(eval("abs(-3)", h)).isEqualTo(V.num(3));
        assertThat(eval("abs(\"x\")", h)).isEqualTo(V.UNDEFINED);
        assertThat(eval("abs(1, 2)", h)).isEqualTo(V.UNDEFINED);
        assertThat(eval("distance(1)", h)).isEqualTo(V.UNDEFINED);
        assertThat(eval("3 in 4", h)).isEqualTo(V.UNDEFINED);
        assertThat(eval("1 == \"1\"", h)).isEqualTo(V.UNDEFINED);
        assertThat(eval("true == true", h)).isEqualTo(V.bool(true));
        assertThat(eval("true < false", h)).isEqualTo(V.UNDEFINED);
        assertThat(eval("1h == 60m", h)).isEqualTo(V.bool(true));
        assertThat(eval("2 * 1h == 2h", h)).isEqualTo(V.bool(true));
        assertThat(eval("1h * 1h", h)).isEqualTo(V.UNDEFINED);
        assertThat(eval("1h / 0", h)).isEqualTo(V.UNDEFINED);
        assertThat(eval("1 / 1h", h)).isEqualTo(V.UNDEFINED);
        assertThat(eval("1h + 1", h)).isEqualTo(V.UNDEFINED);
        assertThat(eval("(1h > 30m) and (2 >= 2)", h)).isEqualTo(V.bool(true));
    }

    @Test
    void aMeasureCalledWithTheWrongNumberOfArgumentsIsUnknownNotAnError() {
        var m = new Interpreter.Measure("m", "c", ExprParser.parse("c.a > 0"));
        var ctx = new Interpreter.Context(Map.of("history", V.cases(List.of(c(1, "a", 3)))), Map.of("m", m), 100 * DAY);
        assertThat(Interpreter.eval(ExprParser.parse("m(1, 2)"), ctx)).isEqualTo(V.UNDEFINED);
        assertThat(Interpreter.eval(ExprParser.parse("history.count(m)"), ctx)).isEqualTo(V.num(1));
        assertThat(Interpreter.eval(ExprParser.parse("history.count(m, m)"), ctx)).isEqualTo(V.UNDEFINED);
        assertThat(Interpreter.eval(ExprParser.parse("history.rate()"), ctx)).isEqualTo(V.UNDEFINED);
        assertThat(Interpreter.eval(ExprParser.parse("history.quantile(a)"), ctx)).isEqualTo(V.UNDEFINED);
        assertThat(Interpreter.eval(ExprParser.parse("history.quantile(a, 3)"), ctx)).isEqualTo(V.UNDEFINED);
        assertThat(Interpreter.eval(ExprParser.parse("history.between(1d)"), ctx)).isEqualTo(V.UNDEFINED);
        assertThat(Interpreter.eval(ExprParser.parse("history.since(3)"), ctx)).isEqualTo(V.UNDEFINED);
    }

    @Test
    void aScriptThatSkipsTheLoadTimeChecksStillNeverClimbsOnAnUnknownName() {
        // the checker rejects an unknown measure at load; this is the run-time floor if it were skipped: the name counts nothing and a comparison with it is not true
        assertThat(Interpreter.test(ExprParser.parse("history.count(typo) >= 1"), new Interpreter.Context(Map.of("history", V.cases(List.of(c(1, "a", 3)))), Map.of(), 100 * DAY))).isFalse();
        assertThat(Interpreter.test(ExprParser.parse("history.rate(typo) >= 0"), new Interpreter.Context(Map.of("history", V.cases(List.of(c(1, "a", 3)))), Map.of(), 100 * DAY))).isFalse();
    }

    @Test
    void theSuggestionsAreCloseNamesOnly() {
        assertThat(Suggest.nearest("agre", List.of("agrees", "rejected"))).isEqualTo("agrees");
        assertThat(Suggest.nearest("zzzz", List.of("agrees", "rejected"))).isNull();
        assertThat(Suggest.nearest("ab", List.of("abc"))).isNull();
        assertThat(Suggest.nearest("AB", List.of("ab"))).isEqualTo("ab");
        assertThat(Suggest.nearest("x", List.of("y"))).isNull();
        assertThat(Suggest.hint("agre", List.of("agrees"))).isEqualTo(" (did you mean 'agrees'?)");
        assertThat(Suggest.hint("zzzz", List.of("agrees"))).isEmpty();
        assertThat(Suggest.distance("kitten", "sitting")).isEqualTo(3);
        assertThat(Suggest.nearest("RATE", List.of("rate"))).isEqualTo("rate");
    }

    @Test
    void anExceptionKnowsItsColumn() {
        assertThat(new ExprException("m", 7).column()).isEqualTo(7);
        assertThatThrownBy(() -> ExprParser.parse("a <")).isInstanceOfSatisfying(ExprException.class, e -> assertThat(e.column()).isEqualTo(4));
    }
}
