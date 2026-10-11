package io.github.llm4j.loom.autonomy.expr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("L1")
class InterpreterTest {

    static final long DAY = 86_400_000L;
    static final long NOW = 100 * DAY;

    /** A case: a day offset from NOW (negative = ago) and fields. */
    static V.Rec c(double daysAgo, Object... kv) {
        Map<String, V> m = new LinkedHashMap<>();
        m.put("at", V.num(NOW - daysAgo * DAY));
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], v(kv[i + 1]));
        return V.rec(m);
    }

    static V v(Object o) {
        if (o instanceof V x) return x;
        if (o instanceof Boolean b) return V.bool(b);
        if (o instanceof Number n) return V.num(n.doubleValue());
        if (o instanceof String s) return V.str(s);
        throw new IllegalArgumentException(String.valueOf(o));
    }

    static Interpreter.Context ctx(List<V.Rec> history, Interpreter.Measure... ms) {
        Map<String, Interpreter.Measure> measures = new LinkedHashMap<>();
        for (Interpreter.Measure m : ms) measures.put(m.name(), m);
        return new Interpreter.Context(Map.of("history", V.cases(history)), measures, NOW);
    }

    static Interpreter.Measure measure(String name, String body) {
        return new Interpreter.Measure(name, "c", ExprParser.parse(body));
    }

    static V eval(String expr, Interpreter.Context ctx) {
        return Interpreter.eval(ExprParser.parse(expr), ctx);
    }

    static V eval(String expr) {
        return eval(expr, ctx(List.of()));
    }

    // ---------- operators ----------

    @Test
    void arithmeticComparisonAndStrings() {
        assertThat(eval("1 + 2 * 3")).isEqualTo(V.num(7));
        assertThat(eval("10 / 4")).isEqualTo(V.num(2.5));
        assertThat(eval("-3 + 1")).isEqualTo(V.num(-2));
        assertThat(eval("3 >= 3 and 2 < 3 and 4 != 5 and 1 <= 1 and 5 > 4")).isEqualTo(V.bool(true));
        assertThat(eval("\"a\" == \"a\"")).isEqualTo(V.bool(true));
        assertThat(eval("\"a\" < \"b\"")).isEqualTo(V.bool(true));
        assertThat(eval("\"x\" != \"y\"")).isEqualTo(V.bool(true));
        assertThat(eval("90% == 0.9")).isEqualTo(V.bool(true));
    }

    @Test
    void durationsAddCompareAndScale() {
        assertThat(eval("1d + 12h")).isEqualTo(V.dur((long) (1.5 * DAY)));
        assertThat(eval("2d - 1d")).isEqualTo(V.dur(DAY));
        assertThat(eval("30d >= 14d")).isEqualTo(V.bool(true));
        assertThat(eval("2 * 1h")).isEqualTo(V.dur(3_600_000L * 2));
        assertThat(eval("1h * 2")).isEqualTo(V.dur(3_600_000L * 2));
        assertThat(eval("1d / 2")).isEqualTo(V.dur(DAY / 2));
        assertThat(eval("30s < 1m")).isEqualTo(V.bool(true));
        assertThat(eval("1d > 5")).isEqualTo(V.UNDEFINED);
    }

    @Test
    void divisionByZeroAndTypeMismatchAreUndefinedNotErrors() {
        assertThat(eval("1 / 0")).isEqualTo(V.UNDEFINED);
        assertThat(eval("1 + \"a\"")).isEqualTo(V.UNDEFINED);
        assertThat(eval("\"a\" < 1")).isEqualTo(V.UNDEFINED);
        assertThat(eval("true + 1")).isEqualTo(V.UNDEFINED);
        assertThat(eval("not 1")).isEqualTo(V.UNDEFINED);
    }

    @Test
    void membership() {
        assertThat(eval("\"legal\" in [\"legal\", \"pricing\"]")).isEqualTo(V.bool(true));
        assertThat(eval("\"news\" in [\"legal\", \"pricing\"]")).isEqualTo(V.bool(false));
        assertThat(eval("2 in [1, 2, 3]")).isEqualTo(V.bool(true));
        assertThat(eval("2 in []")).isEqualTo(V.bool(false));
        assertThat(eval("missing in [1]")).isEqualTo(V.UNDEFINED);
    }

    /** The full three-valued truth tables; U is an undefined name. */
    @ParameterizedTest
    @CsvSource({
            "true and true, true", "true and false, false", "true and U, U", "false and U, false", "U and false, false", "U and true, U", "U and U, U",
            "true or U, true", "U or true, true", "false or U, U", "U or false, U", "false or false, false", "U or U, U",
            "not true, false", "not false, true", "not U, U"})
    void threeValuedLogic(String expr, String expected) {
        String e = expr.replace("U", "nothing.here > 1");
        V v = eval(e);
        switch (expected) {
            case "true" -> assertThat(v).isEqualTo(V.bool(true));
            case "false" -> assertThat(v).isEqualTo(V.bool(false));
            default -> assertThat(v).isEqualTo(V.UNDEFINED);
        }
    }

    @Test
    void undefinedPropagatesThroughArithmeticAndComparison() {
        assertThat(eval("nothing + 1")).isEqualTo(V.UNDEFINED);
        assertThat(eval("nothing > 1")).isEqualTo(V.UNDEFINED);
        assertThat(eval("nothing == nothing")).isEqualTo(V.UNDEFINED);
        assertThat(Interpreter.test(ExprParser.parse("nothing > 1"), ctx(List.of()))).isFalse();
        assertThat(Interpreter.test(ExprParser.parse("not (nothing > 1)"), ctx(List.of()))).isFalse();
    }

    // ---------- collections ----------

    @Test
    void theAgeOfTheEvidenceIsHowLongAgoTheFirstCaseWasAndIsUnknownForNone() {
        var h = List.of(c(30), c(20), c(10, "x", 1));
        assertThat(eval("history.age", ctx(h))).isEqualTo(V.dur(30 * DAY));
        assertThat(eval("history.age >= 30d", ctx(h))).isEqualTo(V.bool(true));
        assertThat(eval("history.age >= 31d", ctx(h))).isEqualTo(V.bool(false));
        assertThat(eval("history.age", ctx(List.of(c(1))))).isEqualTo(V.dur(1 * DAY));       // one case is enough, unlike span
        assertThat(eval("history.age", ctx(List.of()))).isEqualTo(V.UNDEFINED);
        assertThat(Interpreter.test(ExprParser.parse("history.age >= 1d"), ctx(List.of()))).as("no evidence is never old enough").isFalse();
        assertThat(eval("history.last(1).age", ctx(h))).as("of the cases selected").isEqualTo(V.dur(10 * DAY));
    }

    @Test
    void countSpanAndFieldAccessOnTheCollection() {
        var h = List.of(c(30), c(20), c(10, "x", 1));
        assertThat(eval("history.count", ctx(h))).isEqualTo(V.num(3));
        assertThat(eval("history.count()", ctx(h))).isEqualTo(V.num(3));
        assertThat(eval("history.n", ctx(h))).isEqualTo(V.num(3));
        assertThat(eval("history.span", ctx(h))).isEqualTo(V.dur(20 * DAY));
        assertThat(eval("history.span >= 14d", ctx(h))).isEqualTo(V.bool(true));
        assertThat(eval("history.span", ctx(List.of(c(1))))).isEqualTo(V.UNDEFINED);
        assertThat(eval("history.span", ctx(List.of()))).isEqualTo(V.UNDEFINED);
        assertThat(eval("history.count", ctx(List.of()))).isEqualTo(V.num(0));
    }

    @Test
    void whereFiltersByFieldsMeasuresAndCompoundConditions() {
        var h = List.of(c(5, "amount", 100, "tier", "gold"), c(4, "amount", 300, "tier", "gold"), c(3, "amount", 250, "tier", "basic"));
        assertThat(eval("history.where(amount > 200).count", ctx(h))).isEqualTo(V.num(2));
        assertThat(eval("history.where(amount > 200 and tier == \"gold\").count", ctx(h))).isEqualTo(V.num(1));
        var big = measure("big", "c.amount > 200");
        assertThat(eval("history.where(big).count", ctx(h, big))).isEqualTo(V.num(2));
        assertThat(eval("history.where(not big).count", ctx(h, big))).isEqualTo(V.num(1));
        assertThat(eval("history.where(amount > 200).where(tier == \"basic\").count", ctx(h))).isEqualTo(V.num(1));
    }

    @Test
    void aCaseWhereTheConditionIsUndefinedIsNotSelected() {
        var h = List.of(c(2, "amount", 300), c(1));
        assertThat(eval("history.where(amount > 200).count", ctx(h))).isEqualTo(V.num(1));
        assertThat(eval("history.where(not (amount > 200)).count", ctx(h))).isEqualTo(V.num(0));
    }

    @Test
    void lastTakesTheMostRecentInOrderAndCopesWithMoreThanThereAre() {
        var h = List.of(c(5, "k", 1), c(4, "k", 2), c(3, "k", 3), c(2, "k", 4), c(1, "k", 5));
        assertThat(eval("history.last(2).sum(k)", ctx(h))).isEqualTo(V.num(9));
        assertThat(eval("history.last(99).count", ctx(h))).isEqualTo(V.num(5));
        assertThat(eval("history.last(0).count", ctx(h))).isEqualTo(V.num(0));
        assertThat(eval("history.last(-1).count", ctx(h))).isEqualTo(V.num(0));
        assertThat(eval("history.last(2.9).count", ctx(h))).isEqualTo(V.num(2));
    }

    @Test
    void sinceAndBetweenUseTheInjectedTimeAndIncludeTheBoundary() {
        var h = List.of(c(30), c(14), c(13.5), c(1), c(0));
        assertThat(eval("history.since(14d).count", ctx(h))).isEqualTo(V.num(4));
        assertThat(eval("history.since(1d).count", ctx(h))).isEqualTo(V.num(2));
        assertThat(eval("history.since(0s).count", ctx(h))).isEqualTo(V.num(1));
        assertThat(eval("history.between(30d, 14d).count", ctx(h))).isEqualTo(V.num(2));
        assertThat(eval("history.between(2d, 0d).count", ctx(h))).isEqualTo(V.num(2));
    }

    // ---------- aggregates ----------

    @Test
    void countAndRateOfAPredicate() {
        var h = new ArrayList<V.Rec>();
        for (int i = 0; i < 10; i++) h.add(c(10 - i, "ok", i < 9));
        assertThat(eval("history.count(ok)", ctx(h))).isEqualTo(V.num(9));
        assertThat(eval("history.count(not ok)", ctx(h))).isEqualTo(V.num(1));
        V r = eval("history.rate(ok)", ctx(h));
        assertThat(r).isInstanceOf(V.Rat.class);
        assertThat(((V.Rat) r).value()).isEqualTo(0.9);
        assertThat(((V.Rat) r).n()).isEqualTo(10);
        assertThat(eval("history.rate(ok).value", ctx(h))).isEqualTo(V.num(0.9));
        assertThat(eval("history.rate(ok).n", ctx(h))).isEqualTo(V.num(10));
        assertThat(((V.Num) eval("history.rate(ok).lower95", ctx(h))).value()).isCloseTo(0.5958, Offset.offset(1e-3));
        assertThat(((V.Num) eval("history.rate(ok).upper95", ctx(h))).value()).isCloseTo(0.9821, Offset.offset(1e-3));
    }

    @Test
    void aBareComparisonOnARatioUsesItsValueButTheBoundMustBeAskedFor() {
        var h = new ArrayList<V.Rec>();
        for (int i = 0; i < 20; i++) h.add(c(20 - i, "ok", true));
        assertThat(eval("history.rate(ok) >= 0.95", ctx(h))).isEqualTo(V.bool(true));
        assertThat(eval("history.rate(ok).lower95 >= 0.95", ctx(h))).isEqualTo(V.bool(false));
        assertThat(eval("history.rate(ok) == 1", ctx(h))).isEqualTo(V.bool(true));
        assertThat(eval("history.rate(ok) * 100", ctx(h))).isEqualTo(V.num(100));
    }

    @Test
    void rateSkipsCasesWhereThePredicateIsUndefinedAndIsUndefinedWithNone() {
        var h = List.of(c(3, "ok", true), c(2, "ok", false), c(1));
        V r = eval("history.rate(ok)", ctx(h));
        assertThat(((V.Rat) r).n()).isEqualTo(2);
        assertThat(((V.Rat) r).value()).isEqualTo(0.5);
        assertThat(eval("history.rate(ok)", ctx(List.of()))).isEqualTo(V.UNDEFINED);
        assertThat(eval("history.rate(ok)", ctx(List.of(c(1))))).isEqualTo(V.UNDEFINED);
        assertThat(eval("history.rate(ok) >= 0", ctx(List.of()))).isEqualTo(V.UNDEFINED);
        assertThat(eval("history.rate(ok).lower95", ctx(List.of()))).isEqualTo(V.UNDEFINED);
    }

    @Test
    void numericAggregates() {
        var h = List.of(c(5, "v", 1), c(4, "v", 2), c(3, "v", 3), c(2, "v", 4), c(1, "v", 10));
        assertThat(eval("history.mean(v)", ctx(h))).isEqualTo(V.num(4));
        assertThat(eval("history.median(v)", ctx(h))).isEqualTo(V.num(3));
        assertThat(eval("history.min(v)", ctx(h))).isEqualTo(V.num(1));
        assertThat(eval("history.max(v)", ctx(h))).isEqualTo(V.num(10));
        assertThat(eval("history.sum(v)", ctx(h))).isEqualTo(V.num(20));
        assertThat(eval("history.quantile(v, 0.5)", ctx(h))).isEqualTo(V.num(3));
        assertThat(eval("history.quantile(v, 0)", ctx(h))).isEqualTo(V.num(1));
        assertThat(eval("history.quantile(v, 1)", ctx(h))).isEqualTo(V.num(10));
        assertThat(((V.Num) eval("history.quantile(v, 0.9)", ctx(h))).value()).isCloseTo(7.6, Offset.offset(1e-9));
        assertThat(eval("history.last(4).median(v)", ctx(h))).isEqualTo(V.num(3.5));
    }

    @Test
    void numericAggregatesSkipUndefinedAndAreUndefinedOverNothing() {
        var h = List.of(c(3, "v", 2), c(2), c(1, "v", 4));
        assertThat(eval("history.mean(v)", ctx(h))).isEqualTo(V.num(3));
        assertThat(eval("history.median(v)", ctx(h))).isEqualTo(V.num(3));
        assertThat(eval("history.mean(v)", ctx(List.of()))).isEqualTo(V.UNDEFINED);
        assertThat(eval("history.median(v)", ctx(List.of(c(1))))).isEqualTo(V.UNDEFINED);
        assertThat(eval("history.min(v)", ctx(List.of()))).isEqualTo(V.UNDEFINED);
        assertThat(eval("history.max(v)", ctx(List.of()))).isEqualTo(V.UNDEFINED);
        assertThat(eval("history.sum(v)", ctx(List.of()))).isEqualTo(V.num(0));
        assertThat(eval("history.mean(v) <= 1000", ctx(List.of()))).isEqualTo(V.UNDEFINED);
    }

    @Test
    void durationAggregatesStayDurations() {
        var h = List.of(c(3, "t", V.dur(10_000)), c(2, "t", V.dur(30_000)));
        assertThat(eval("history.mean(t)", ctx(h))).isEqualTo(V.dur(20_000));
        assertThat(eval("history.median(t) >= 15s", ctx(h))).isEqualTo(V.bool(true));
        assertThat(eval("history.max(t)", ctx(h))).isEqualTo(V.dur(30_000));
    }

    @Test
    void anyAndAll() {
        var h = List.of(c(3, "ok", true), c(2, "ok", false), c(1));
        assertThat(eval("history.any(ok)", ctx(h))).isEqualTo(V.bool(true));
        assertThat(eval("history.any(not ok)", ctx(h))).isEqualTo(V.bool(true));
        assertThat(eval("history.all(ok)", ctx(h))).isEqualTo(V.bool(false));
        assertThat(eval("history.all(ok)", ctx(List.of(c(1, "ok", true))))).isEqualTo(V.bool(true));
        assertThat(eval("history.any(ok)", ctx(List.of()))).isEqualTo(V.bool(false));
        assertThat(eval("history.all(ok)", ctx(List.of()))).isEqualTo(V.UNDEFINED);
    }

    // ---------- groups ----------

    @Test
    void groupingGivesOneValuePerGroupAndListMethodsReduceThem() {
        var h = List.of(c(6, "tier", "gold", "ok", true), c(5, "tier", "gold", "ok", true), c(4, "tier", "basic", "ok", false),
                c(3, "tier", "basic", "ok", true), c(2, "tier", "basic", "ok", true));
        assertThat(eval("history.by(tier).count().min()", ctx(h))).isEqualTo(V.num(2));
        assertThat(eval("history.by(tier).count().max()", ctx(h))).isEqualTo(V.num(3));
        assertThat(eval("history.by(tier).count().count", ctx(h))).isEqualTo(V.num(2));
        assertThat(eval("history.by(tier).rate(ok).min() >= 0.6", ctx(h))).isEqualTo(V.bool(true));
        assertThat(eval("history.by(tier).rate(ok).min() >= 0.7", ctx(h))).isEqualTo(V.bool(false));
        assertThat(eval("history.by(tier).sum(amount_missing).max()", ctx(h))).isEqualTo(V.num(0));
    }

    // ---------- measures ----------

    @Test
    void measuresTakePrecedenceAreComposableAndMemoised() {
        var h = List.of(c(3, "a", 1, "b", 2), c(2, "a", 5, "b", 2), c(1, "a", 3, "b", 3));
        var diff = measure("diff", "c.a - c.b");
        var large = measure("large", "diff(c) >= 1");
        assertThat(eval("history.where(large).count", ctx(h, diff, large))).isEqualTo(V.num(1));
        assertThat(((V.Num) eval("history.mean(diff)", ctx(h, diff, large))).value()).isCloseTo(2.0 / 3, Offset.offset(1e-9));
        assertThat(eval("history.sum(diff)", ctx(h, diff, large))).isEqualTo(V.num(2));
    }

    @Test
    void aMeasureIsEvaluatedOncePerCasePerEvaluation() {
        int[] calls = {0};
        var h = List.of(c(2, "a", 1), c(1, "a", 2));
        var counted = new Interpreter.Measure("counted", "c", ExprParser.parse("tally(c.a) > 0"));
        Interpreter.Context ctx = new Interpreter.Context(Map.of("history", V.cases(h)), Map.of("counted", counted), NOW,
                Map.of("tally", args -> { calls[0]++; return args.get(0); }));
        V v = Interpreter.eval(ExprParser.parse("history.count(counted) + history.count(counted) + history.rate(counted).n"), ctx);
        assertThat(v).isEqualTo(V.num(6));
        assertThat(calls[0]).isEqualTo(2);
    }

    @Test
    void aMeasureCalledOnAnythingButACaseIsUndefined() {
        var m = measure("m", "c.a > 0");
        assertThat(eval("m(1)", ctx(List.of(), m))).isEqualTo(V.UNDEFINED);
        assertThat(eval("m()", ctx(List.of(), m))).isEqualTo(V.UNDEFINED);
    }

    @Test
    void ladderParametersAndTheCurrentCaseAreVisible() {
        Interpreter.Context ctx = new Interpreter.Context(Map.of("history", V.cases(List.of(c(1, "views", 900), c(2, "views", 1500))),
                "baseline", V.num(1000), "case", c(0, "amount", 250, "topic", "legal")), Map.of(), NOW);
        assertThat(Interpreter.eval(ExprParser.parse("history.mean(views) >= 0.8 * baseline"), ctx)).isEqualTo(V.bool(true));
        assertThat(Interpreter.eval(ExprParser.parse("case.amount > 200 or case.topic in [\"legal\"]"), ctx)).isEqualTo(V.bool(true));
        assertThat(Interpreter.eval(ExprParser.parse("history.where(views > baseline).count"), ctx)).isEqualTo(V.num(1));
    }

    @Test
    void builtInFunctions() {
        assertThat(eval("distance(\"abcd\", \"abXd\")")).isEqualTo(V.num(0.25));
        assertThat(eval("distance(\"a\", 1)")).isEqualTo(V.UNDEFINED);
        assertThat(eval("abs(-3)")).isEqualTo(V.num(3));
        assertThat(eval("unknown_fn(1)")).isEqualTo(V.UNDEFINED);
    }

    @Test
    void unknownMethodsAndWrongReceiversAreUndefinedNotExceptions() {
        assertThat(eval("history.nonsense(1)", ctx(List.of()))).isEqualTo(V.UNDEFINED);
        assertThat(eval("history.count.where(1)", ctx(List.of()))).isEqualTo(V.UNDEFINED);
        assertThat(eval("1.lower95")).isEqualTo(V.UNDEFINED);
        assertThat(eval("history.last(\"x\").count", ctx(List.of()))).isEqualTo(V.UNDEFINED);
    }

    @Test
    void nestedRecordFieldsReadLikeTheCase() {
        var h = List.of(c(1, "response", V.rec(Map.of("kind", V.str("edit"), "choice", V.str("reject")))));
        assertThat(eval("history.count(c_kind)", ctx(h, measure("c_kind", "c.response.kind == \"edit\"")))).isEqualTo(V.num(1));
        assertThat(eval("history.where(response.kind == \"edit\").count", ctx(h))).isEqualTo(V.num(1));
        assertThat(eval("history.where(response.nope == \"edit\").count", ctx(h))).isEqualTo(V.num(0));
    }

    @Test
    void aSelfReferencingMeasureTerminatesAsUndefined() {
        var loop = measure("loop", "loop(c)");
        assertThat(eval("history.count(loop)", ctx(List.of(c(1)), loop))).isEqualTo(V.num(0));
    }
}
