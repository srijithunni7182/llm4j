package io.github.llm4j.loom.autonomy.expr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Evaluates a ladder expression. The language is total: there are no loops, no assignment and no
 * way to reach outside the values handed in, so every evaluation ends and has no effect. A value
 * that cannot be worked out is {@link V#UNDEFINED}, which propagates; {@link #test} treats anything
 * but {@code true} as {@code false}, which is what makes missing data fail safe.
 */
public final class Interpreter {

    /**
     * A named function of one case: {@code measure edit_size(c) = distance(c.proposal, c.final)}.
     */
    public record Measure(String name, String param, Expr body) {}

    /** A built-in function supplied by the host (the runtime's own primitives). */
    public interface Function {
        V apply(List<V> args);
    }

    /**
     * What an expression can see. {@code vars} holds {@code history}, {@code case} (for escalate)
     * and ladder parameters; {@code nowMillis} is the injected time {@code since} counts back from.
     */
    public record Context(
            Map<String, V> vars,
            Map<String, Measure> measures,
            long nowMillis,
            Map<String, Function> functions) {
        public Context(Map<String, V> vars, Map<String, Measure> measures, long nowMillis) {
            this(vars, measures, nowMillis, Map.of());
        }
    }

    private static final int MAX_MEASURE_DEPTH = 32;

    private final Context ctx;
    private final Map<V.Rec, Map<String, V>> memo = new IdentityHashMap<>();
    private int measureDepth;

    private Interpreter(Context ctx) {
        this.ctx = ctx;
    }

    public static V eval(Expr e, Context ctx) {
        Interpreter run = new Interpreter(ctx);
        V caseVar = ctx.vars().get("case");
        return run.eval(e, new Scope(ctx.vars(), caseVar instanceof V.Rec r ? r : null));
    }

    /** True only when the expression is the boolean {@code true}. */
    public static boolean test(Expr e, Context ctx) {
        return V.isTrue(eval(e, ctx));
    }

    private record Scope(Map<String, V> vars, V.Rec current) {}

    // ---------- expressions ----------

    private V eval(Expr e, Scope s) {
        if (e instanceof Expr.Lit l) return l.value();
        if (e instanceof Expr.Name n) return name(n.name(), s);
        if (e instanceof Expr.Field f) return field(eval(f.target(), s), f.name());
        if (e instanceof Expr.Call c) return call(eval(c.target(), s), c.method(), c.args(), s);
        if (e instanceof Expr.Fn f) return fn(f, s);
        if (e instanceof Expr.Bin b) return bin(b, s);
        if (e instanceof Expr.Not n) {
            V v = eval(n.operand(), s);
            return v instanceof V.Bool b ? V.bool(!b.value()) : V.UNDEFINED;
        }
        if (e instanceof Expr.Neg n) {
            V v = eval(n.operand(), s);
            if (v instanceof V.Num x) return V.num(-x.value());
            if (v instanceof V.Dur d) return V.dur(-d.millis());
            return V.UNDEFINED;
        }
        if (e instanceof Expr.ListLit l) {
            List<V> items = new ArrayList<>();
            for (Expr x : l.items()) items.add(eval(x, s));
            return new V.Lst(items);
        }
        return V.UNDEFINED;
    }

    private V name(String name, Scope s) {
        V v = s.vars().get(name);
        if (v != null) return v;
        Measure m = ctx.measures().get(name);
        if (m != null && s.current() != null) return apply(m, s.current());
        if (s.current() != null) return s.current().get(name);
        return V.UNDEFINED;
    }

    private V fn(Expr.Fn f, Scope s) {
        Measure m = ctx.measures().get(f.name());
        if (m != null) {
            if (f.args().size() != 1) return V.UNDEFINED;
            return eval(f.args().get(0), s) instanceof V.Rec rec ? apply(m, rec) : V.UNDEFINED;
        }
        List<V> args = new ArrayList<>();
        for (Expr a : f.args()) args.add(eval(a, s));
        Function host = ctx.functions().get(f.name());
        if (host != null) return host.apply(args);
        switch (f.name()) {
            case "distance":
                return args.size() == 2 ? Distance.of(args.get(0), args.get(1)) : V.UNDEFINED;
            case "abs":
                return args.size() == 1 && args.get(0) instanceof V.Num n
                        ? V.num(Math.abs(n.value()))
                        : V.UNDEFINED;
            default:
                return V.UNDEFINED;
        }
    }

    /** A measure applied to a case, worked out once per case per evaluation. */
    private V apply(Measure m, V.Rec c) {
        Map<String, V> perCase = memo.computeIfAbsent(c, k -> new LinkedHashMap<>());
        V cached = perCase.get(m.name());
        if (cached != null) return cached;
        if (measureDepth >= MAX_MEASURE_DEPTH) return V.UNDEFINED;
        measureDepth++;
        try {
            Map<String, V> vars = new LinkedHashMap<>(ctx.vars());
            vars.remove("history");
            vars.remove("case");
            vars.put(m.param(), c);
            V result = eval(m.body(), new Scope(vars, c));
            perCase.put(m.name(), result);
            return result;
        } finally {
            measureDepth--;
        }
    }

    // ---------- operators ----------

    private V bin(Expr.Bin b, Scope s) {
        String op = b.op();
        if (op.equals("and") || op.equals("or")) return logic(op, b, s);
        V l = eval(b.left(), s);
        V r = eval(b.right(), s);
        if (l.isUndefined() || r.isUndefined()) return V.UNDEFINED;
        switch (op) {
            case "+":
            case "-":
            case "*":
            case "/":
                return arithmetic(op, l, r);
            case "in":
                return r instanceof V.Lst list
                        ? V.bool(list.items().stream().anyMatch(x -> equal(l, x)))
                        : V.UNDEFINED;
            case "==":
            case "!=":
                {
                    Optional<Boolean> eq =
                            comparable(l, r) ? Optional.of(equal(l, r)) : Optional.empty();
                    return eq.map(x -> V.bool(op.equals("==") == x)).orElse(V.UNDEFINED);
                }
            default:
                return order(op, l, r);
        }
    }

    private V logic(String op, Expr.Bin b, Scope s) {
        boolean and = op.equals("and");
        V l = eval(b.left(), s);
        if (l instanceof V.Bool lb && lb.value() != and) return l; // false and _ , true or _
        V r = eval(b.right(), s);
        if (r instanceof V.Bool rb && rb.value() != and) return r;
        if (l instanceof V.Bool && r instanceof V.Bool) return V.bool(and);
        return V.UNDEFINED;
    }

    private static V arithmetic(String op, V l, V r) {
        if (l instanceof V.Dur a && r instanceof V.Dur b) {
            if (op.equals("+")) return V.dur(a.millis() + b.millis());
            if (op.equals("-")) return V.dur(a.millis() - b.millis());
            return V.UNDEFINED;
        }
        if (l instanceof V.Dur a && isNumber(r)) {
            double n = number(r);
            if (op.equals("*")) return V.dur(Math.round(a.millis() * n));
            if (op.equals("/")) return n == 0 ? V.UNDEFINED : V.dur(Math.round(a.millis() / n));
            return V.UNDEFINED;
        }
        if (isNumber(l) && r instanceof V.Dur b)
            return op.equals("*") ? V.dur(Math.round(number(l) * b.millis())) : V.UNDEFINED;
        if (!isNumber(l) || !isNumber(r)) return V.UNDEFINED;
        double x = number(l), y = number(r);
        switch (op) {
            case "+":
                return V.num(x + y);
            case "-":
                return V.num(x - y);
            case "*":
                return V.num(x * y);
            default:
                return y == 0 ? V.UNDEFINED : V.num(x / y);
        }
    }

    private static V order(String op, V l, V r) {
        int cmp;
        if (isNumber(l) && isNumber(r)) cmp = Double.compare(number(l), number(r));
        else if (l instanceof V.Dur a && r instanceof V.Dur b)
            cmp = Long.compare(a.millis(), b.millis());
        else if (l instanceof V.Str a && r instanceof V.Str b) cmp = a.value().compareTo(b.value());
        else return V.UNDEFINED;
        switch (op) {
            case "<":
                return V.bool(cmp < 0);
            case "<=":
                return V.bool(cmp <= 0);
            case ">":
                return V.bool(cmp > 0);
            case ">=":
                return V.bool(cmp >= 0);
            default:
                return V.UNDEFINED;
        }
    }

    private static boolean isNumber(V v) {
        return v instanceof V.Num || v instanceof V.Rat;
    }

    private static double number(V v) {
        return v instanceof V.Num n ? n.value() : ((V.Rat) v).value();
    }

    private static boolean comparable(V l, V r) {
        if (isNumber(l) && isNumber(r)) return true;
        return l.getClass() == r.getClass();
    }

    private static boolean equal(V l, V r) {
        if (isNumber(l) && isNumber(r)) return Double.compare(number(l), number(r)) == 0;
        return l.equals(r);
    }

    // ---------- fields and methods ----------

    private V field(V target, String name) {
        if (target instanceof V.Rec r) return r.get(name);
        if (target instanceof V.Cs cs) {
            switch (name) {
                case "count":
                case "n":
                    return V.num(cs.cases().size());
                case "span":
                    return span(cs);
                case "age":
                    return age(cs);
                default:
                    return V.UNDEFINED;
            }
        }
        if (target instanceof V.Gr g)
            return name.equals("count") ? V.num(g.groups().size()) : V.UNDEFINED;
        if (target instanceof V.Lst l)
            return name.equals("count") ? V.num(l.items().size()) : V.UNDEFINED;
        if (target instanceof V.Rat r) {
            switch (name) {
                case "value":
                    return V.num(r.value());
                case "n":
                    return V.num(r.n());
                case "lower95":
                    return V.num(r.lower95());
                case "upper95":
                    return V.num(r.upper95());
                default:
                    return V.UNDEFINED;
            }
        }
        return V.UNDEFINED;
    }

    /**
     * How long ago the first case happened: the age of the evidence, to now. Unknown for no cases.
     */
    private V age(V.Cs cs) {
        if (cs.cases().isEmpty()) return V.UNDEFINED;
        return V.asNumber(cs.cases().get(0).get("at"))
                .map(first -> (V) V.dur(Math.max(0, ctx.nowMillis() - Math.round(first))))
                .orElse(V.UNDEFINED);
    }

    private static V span(V.Cs cs) {
        if (cs.cases().size() < 2) return V.UNDEFINED;
        Optional<Double> first = V.asNumber(cs.cases().get(0).get("at"));
        Optional<Double> last = V.asNumber(cs.cases().get(cs.cases().size() - 1).get("at"));
        if (first.isEmpty() || last.isEmpty()) return V.UNDEFINED;
        return V.dur(Math.round(last.get() - first.get()));
    }

    private V call(V target, String method, List<Expr> args, Scope s) {
        if (target instanceof V.Cs cs) return onCases(cs, method, args, s);
        if (target instanceof V.Gr g) return onGroups(g, method, args, s);
        if (target instanceof V.Lst l) return onList(l, method);
        return V.UNDEFINED;
    }

    private V perCase(Expr arg, V.Rec c, Scope s) {
        return eval(arg, new Scope(s.vars(), c));
    }

    private V onCases(V.Cs cs, String method, List<Expr> args, Scope s) {
        List<V.Rec> cases = cs.cases();
        switch (method) {
            case "where":
                {
                    if (args.size() != 1) return V.UNDEFINED;
                    List<V.Rec> out = new ArrayList<>();
                    for (V.Rec c : cases) if (V.isTrue(perCase(args.get(0), c, s))) out.add(c);
                    return V.cases(out);
                }
            case "last":
                {
                    if (args.size() != 1 || !(eval(args.get(0), s) instanceof V.Num n))
                        return V.UNDEFINED;
                    int k = (int) Math.max(0, Math.min(cases.size(), Math.floor(n.value())));
                    return V.cases(cases.subList(cases.size() - k, cases.size()));
                }
            case "since":
                {
                    if (args.size() != 1 || !(eval(args.get(0), s) instanceof V.Dur d))
                        return V.UNDEFINED;
                    return window(cases, ctx.nowMillis() - d.millis(), Long.MAX_VALUE);
                }
            case "between":
                {
                    if (args.size() != 2
                            || !(eval(args.get(0), s) instanceof V.Dur from)
                            || !(eval(args.get(1), s) instanceof V.Dur to)) return V.UNDEFINED;
                    return window(
                            cases, ctx.nowMillis() - from.millis(), ctx.nowMillis() - to.millis());
                }
            case "by":
                {
                    if (args.size() != 1) return V.UNDEFINED;
                    Map<String, List<V.Rec>> groups = new LinkedHashMap<>();
                    for (V.Rec c : cases) {
                        String key = key(perCase(args.get(0), c, s));
                        if (key != null) groups.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
                    }
                    Map<String, V.Cs> out = new LinkedHashMap<>();
                    groups.forEach((k, v) -> out.put(k, V.cases(v)));
                    return new V.Gr(out);
                }
            case "count":
                if (args.isEmpty()) return V.num(cases.size());
                return args.size() == 1 ? V.num(trues(cases, args.get(0), s)) : V.UNDEFINED;
            case "rate":
                {
                    if (args.size() != 1) return V.UNDEFINED;
                    int k = 0, n = 0;
                    for (V.Rec c : cases) {
                        V v = perCase(args.get(0), c, s);
                        if (v instanceof V.Bool b) {
                            n++;
                            if (b.value()) k++;
                        }
                    }
                    return V.ratio(k, n);
                }
            case "any":
            case "all":
                {
                    if (args.size() != 1) return V.UNDEFINED;
                    int t = 0, f = 0;
                    for (V.Rec c : cases) {
                        V v = perCase(args.get(0), c, s);
                        if (v instanceof V.Bool b) {
                            if (b.value()) t++;
                            else f++;
                        }
                    }
                    if (method.equals("any")) return V.bool(t > 0);
                    return t + f == 0 ? V.UNDEFINED : V.bool(f == 0);
                }
            case "mean":
            case "median":
            case "min":
            case "max":
            case "sum":
            case "quantile":
                return numeric(cases, method, args, s);
            default:
                return V.UNDEFINED;
        }
    }

    private static V window(List<V.Rec> cases, long from, long to) {
        List<V.Rec> out = new ArrayList<>();
        for (V.Rec c : cases) {
            Optional<Double> at = V.asNumber(c.get("at"));
            if (at.isPresent() && at.get() >= from && at.get() <= to) out.add(c);
        }
        return V.cases(out);
    }

    private static String key(V v) {
        if (v instanceof V.Str s) return s.value();
        if (v instanceof V.Num n)
            return n.value() == Math.rint(n.value())
                    ? String.valueOf((long) n.value())
                    : String.valueOf(n.value());
        if (v instanceof V.Bool b) return String.valueOf(b.value());
        return null;
    }

    private int trues(List<V.Rec> cases, Expr arg, Scope s) {
        int k = 0;
        for (V.Rec c : cases) if (V.isTrue(perCase(arg, c, s))) k++;
        return k;
    }

    private V numeric(List<V.Rec> cases, String method, List<Expr> args, Scope s) {
        boolean quantile = method.equals("quantile");
        if (args.size() != (quantile ? 2 : 1)) return V.UNDEFINED;
        double q = 0;
        if (quantile) {
            if (!(eval(args.get(1), s) instanceof V.Num qn) || qn.value() < 0 || qn.value() > 1)
                return V.UNDEFINED;
            q = qn.value();
        }
        List<Double> xs = new ArrayList<>();
        boolean allDur = true;
        for (V.Rec c : cases) {
            V v = perCase(args.get(0), c, s);
            Optional<Double> d =
                    v instanceof V.Dur || v instanceof V.Num ? V.asNumber(v) : Optional.empty();
            if (d.isPresent()) {
                xs.add(d.get());
                allDur &= v instanceof V.Dur;
            }
        }
        if (xs.isEmpty()) return method.equals("sum") ? V.num(0) : V.UNDEFINED;
        double result = reduce(method, xs, q);
        return allDur ? V.dur(Math.round(result)) : V.num(result);
    }

    private static double reduce(String method, List<Double> xs, double q) {
        switch (method) {
            case "sum":
                return xs.stream().mapToDouble(Double::doubleValue).sum();
            case "mean":
                return xs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            case "min":
                return Collections.min(xs);
            case "max":
                return Collections.max(xs);
            case "median":
                return quantile(xs, 0.5);
            default:
                return quantile(xs, q);
        }
    }

    /** Linear interpolation between the closest ranks. */
    private static double quantile(List<Double> xs, double q) {
        List<Double> sorted = new ArrayList<>(xs);
        Collections.sort(sorted);
        double pos = q * (sorted.size() - 1);
        int lo = (int) Math.floor(pos), hi = (int) Math.ceil(pos);
        return sorted.get(lo) + (sorted.get(hi) - sorted.get(lo)) * (pos - lo);
    }

    private V onGroups(V.Gr g, String method, List<Expr> args, Scope s) {
        switch (method) {
            case "count":
            case "rate":
            case "any":
            case "all":
            case "mean":
            case "median":
            case "min":
            case "max":
            case "sum":
            case "quantile":
                {
                    List<V> out = new ArrayList<>();
                    for (V.Cs group : g.groups().values()) {
                        V v = onCases(group, method, args, s);
                        if (!v.isUndefined()) out.add(v);
                    }
                    return new V.Lst(out);
                }
            default:
                return V.UNDEFINED;
        }
    }

    private static V onList(V.Lst l, String method) {
        List<Double> xs = new ArrayList<>();
        for (V v : l.items()) V.asNumber(v).ifPresent(xs::add);
        switch (method) {
            case "count":
                return V.num(l.items().size());
            case "sum":
                return V.num(xs.stream().mapToDouble(Double::doubleValue).sum());
            case "min":
                return xs.isEmpty() ? V.UNDEFINED : V.num(Collections.min(xs));
            case "max":
                return xs.isEmpty() ? V.UNDEFINED : V.num(Collections.max(xs));
            case "mean":
                return xs.isEmpty()
                        ? V.UNDEFINED
                        : V.num(xs.stream().mapToDouble(Double::doubleValue).average().orElse(0));
            default:
                return V.UNDEFINED;
        }
    }
}
