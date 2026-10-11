package io.github.llm4j.loom.autonomy.expr;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.autonomy.expr.Checker.T;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Random expressions and random histories against the rules that must always hold. Reproduce a failure with {@code -Dloom.ladder.seed=…}. */
@Tag("L5")
class ExprPropertyTest {

    private static final long SEED = Long.getLong("loom.ladder.seed", 20261011L);
    private static final int ROUNDS = Integer.getInteger("loom.ladder.rounds", 5_000);
    private static final long NOW = 1_000L * 86_400_000L;

    private static final String[] PREDICATES = {"ok", "big", "not ok", "amount > 100", "big and ok", "ok or big", "tier == \"gold\"", "edit_size < 0.1"};
    private static final String[] NUMBERS = {"amount", "edit_size", "review_time", "amount * 2", "edit_size + 1"};

    private static String window(Random r) {
        return switch (r.nextInt(6)) {
            case 0 -> "history";
            case 1 -> "history.last(" + r.nextInt(60) + ")";
            case 2 -> "history.since(" + (1 + r.nextInt(30)) + "d)";
            case 3 -> "history.where(" + pick(r, PREDICATES) + ")";
            case 4 -> "history.last(" + (1 + r.nextInt(50)) + ").where(" + pick(r, PREDICATES) + ")";
            default -> "history.between(" + (10 + r.nextInt(20)) + "d, " + r.nextInt(10) + "d)";
        };
    }

    private static String pick(Random r, String[] xs) {
        return xs[r.nextInt(xs.length)];
    }

    /** A random well-typed yes/no expression, as a climb or slip might be written. */
    private static String condition(Random r, int depth) {
        if (depth > 0 && r.nextInt(3) == 0) {
            String op = r.nextBoolean() ? " and " : " or ";
            return "(" + condition(r, depth - 1) + op + condition(r, depth - 1) + ")";
        }
        if (depth > 0 && r.nextInt(8) == 0) return "not " + condition(r, depth - 1);
        String w = window(r);
        double x = r.nextInt(100) / 100.0;
        return switch (r.nextInt(9)) {
            case 0 -> w + ".count >= " + r.nextInt(300);
            case 1 -> w + ".rate(" + pick(r, PREDICATES) + ") >= " + x;
            case 2 -> w + ".rate(" + pick(r, PREDICATES) + ").lower95 >= " + x;
            case 3 -> w + ".count(" + pick(r, PREDICATES) + ") == " + r.nextInt(5);
            case 4 -> numeric(r, w, "mean", " <= ");
            case 5 -> numeric(r, w, "median", " > ");
            case 6 -> w + ".span >= " + (1 + r.nextInt(30)) + "d";
            case 7 -> w + ".by(tier).rate(" + pick(r, PREDICATES) + ").min() >= " + x;
            default -> w + ".any(" + pick(r, PREDICATES) + ")";
        };
    }

    /** A threshold of the right kind: a duration for review_time, a number otherwise. */
    private static String numeric(Random r, String window, String method, String cmp) {
        String m = pick(r, NUMBERS);
        return window + "." + method + "(" + m + ")" + cmp + (m.equals("review_time") ? (1 + r.nextInt(100)) + "s" : String.valueOf(r.nextInt(300)));
    }

    private static Map<String, Interpreter.Measure> measures() {
        Map<String, Interpreter.Measure> ms = new LinkedHashMap<>();
        ms.put("big", new Interpreter.Measure("big", "c", ExprParser.parse("c.amount > 250")));
        ms.put("ok", new Interpreter.Measure("ok", "c", ExprParser.parse("c.accepted")));
        ms.put("edit_size", new Interpreter.Measure("edit_size", "c", ExprParser.parse("distance(c.proposal, c.final)")));
        return ms;
    }

    private static Checker checker(Map<String, Interpreter.Measure> ms) {
        Map<String, T> fields = new LinkedHashMap<>();
        fields.put("amount", T.NUM);
        fields.put("tier", T.STR);
        fields.put("accepted", T.BOOL);
        fields.put("review_time", T.DUR);
        fields.put("proposal", T.ANY);
        fields.put("final", T.ANY);
        return new Checker(fields, Map.of(), ms, true, false);
    }

    private static List<V.Rec> history(Random r, int size) {
        List<V.Rec> cases = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            Map<String, V> f = new LinkedHashMap<>();
            f.put("at", V.num(NOW - (size - i) * 86_400_000L / (1 + r.nextInt(3))));
            if (r.nextInt(10) > 0) f.put("amount", V.num(r.nextInt(500)));
            if (r.nextInt(10) > 0) f.put("tier", V.str(r.nextBoolean() ? "gold" : "basic"));
            if (r.nextInt(5) > 0) f.put("accepted", V.bool(r.nextInt(4) > 0));
            if (r.nextInt(5) > 0) f.put("review_time", V.dur(r.nextInt(120_000)));
            if (r.nextInt(5) > 0) {
                String text = "text" + r.nextInt(50);
                f.put("proposal", V.str(text));
                f.put("final", V.str(r.nextBoolean() ? text : text + "x"));
            }
            cases.add(V.rec(f));
        }
        return cases;
    }

    @Test
    void everyCheckedExpressionTerminatesWithoutThrowingOnAnyHistory() {
        Random r = new Random(SEED);
        Map<String, Interpreter.Measure> ms = measures();
        Checker checker = checker(ms);
        for (int i = 0; i < ROUNDS; i++) {
            String src = condition(r, 2);
            Expr e = ExprParser.parse(src);
            assertThat(checker.condition(e)).as(src).isIn(T.BOOL, T.ANY);
            var ctx = new Interpreter.Context(Map.of("history", V.cases(history(r, r.nextInt(80)))), ms, NOW);
            V v = Interpreter.eval(e, ctx);
            assertThat(v).as(src).isInstanceOfAny(V.Bool.class, V.Undefined.class);
        }
    }

    @Test
    void noDataNeverMakesAClimbTrue() {
        Random r = new Random(SEED + 1);
        Map<String, Interpreter.Measure> ms = measures();
        for (int i = 0; i < ROUNDS; i++) {
            // a climb is written as thresholds over aggregates joined by 'and'; over no cases each aggregate threshold is false or undefined
            String src = "history.rate(" + pick(r, PREDICATES) + ") >= " + (r.nextInt(100) / 100.0) + " and " + condition(r, 0);
            var ctx = new Interpreter.Context(Map.of("history", V.cases(List.of())), ms, NOW);
            assertThat(Interpreter.test(ExprParser.parse(src), ctx)).as(src).isFalse();
            String lower = "history.rate(" + pick(r, PREDICATES) + ").lower95 >= " + (r.nextInt(100) / 100.0);
            assertThat(Interpreter.test(ExprParser.parse(lower), ctx)).as(lower).isFalse();
        }
    }

    @Test
    void allUnknownValuesNeverMakeARateThresholdTrue() {
        Random r = new Random(SEED + 2);
        Map<String, Interpreter.Measure> ms = measures();
        for (int i = 0; i < 500; i++) {
            List<V.Rec> blanks = new ArrayList<>();
            int n = 1 + r.nextInt(50);
            for (int k = 0; k < n; k++) blanks.add(V.rec(Map.of("at", V.num(NOW - k * 1000L))));
            var ctx = new Interpreter.Context(Map.of("history", V.cases(blanks)), ms, NOW);
            String src = "history.rate(ok) >= " + (r.nextInt(100) / 100.0);
            assertThat(Interpreter.test(ExprParser.parse(src), ctx)).as(src).isFalse();
        }
    }

    @Test
    void anExpressionNeverChangesTheHistoryItIsGiven() {
        Random r = new Random(SEED + 3);
        Map<String, Interpreter.Measure> ms = measures();
        for (int i = 0; i < 500; i++) {
            List<V.Rec> h = history(r, 40);
            var snapshot = new ArrayList<>(h);
            Interpreter.eval(ExprParser.parse(condition(r, 2)), new Interpreter.Context(Map.of("history", V.cases(h)), ms, NOW));
            assertThat(h).isEqualTo(snapshot);
        }
    }

    @Test
    void tighteningAThresholdNeverMakesALowerBoundRuleEasier() {
        Random r = new Random(SEED + 4);
        Map<String, Interpreter.Measure> ms = measures();
        for (int i = 0; i < 1_000; i++) {
            var ctx = new Interpreter.Context(Map.of("history", V.cases(history(r, 5 + r.nextInt(60)))), ms, NOW);
            double lo = r.nextInt(50) / 100.0, hi = lo + r.nextInt(50) / 100.0;
            boolean easy = Interpreter.test(ExprParser.parse("history.rate(ok).lower95 >= " + hi), ctx);
            boolean hard = Interpreter.test(ExprParser.parse("history.rate(ok).lower95 >= " + lo), ctx);
            if (easy) assertThat(hard).isTrue();
        }
    }

    @Test
    void theParserOnlyEverFailsWithAnExprExceptionOnArbitraryInput() {
        Random r = new Random(SEED + 5);
        String alphabet = "abc_ .,()[]\"'<>=!+-*/%0123456789dhmsandornotintruefalse\n#";
        for (int i = 0; i < 20_000; i++) {
            StringBuilder b = new StringBuilder();
            int len = r.nextInt(40);
            for (int k = 0; k < len; k++) b.append(alphabet.charAt(r.nextInt(alphabet.length())));
            try {
                ExprParser.parse(b.toString());
            } catch (ExprException expected) {
                // fine
            }
        }
    }

    @Test
    void checkedExpressionsSurviveTheirOwnShownFormAndEvaluateTheSame() {
        Random r = new Random(SEED + 6);
        Map<String, Interpreter.Measure> ms = measures();
        for (int i = 0; i < 1_000; i++) {
            String src = condition(r, 2);
            Expr a = ExprParser.parse(src);
            Expr b = ExprParser.parse(a.show());
            assertThat(b.show()).isEqualTo(a.show());
            var ctx = new Interpreter.Context(Map.of("history", V.cases(history(r, 30))), ms, NOW);
            assertThat(Interpreter.eval(b, ctx)).as(src).isEqualTo(Interpreter.eval(a, ctx));
        }
    }
}
