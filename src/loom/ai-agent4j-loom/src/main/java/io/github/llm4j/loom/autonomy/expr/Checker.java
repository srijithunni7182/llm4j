package io.github.llm4j.loom.autonomy.expr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Works out what an expression is, before anything runs: every name, method and operand is checked, so a misspelling or a number compared with
 * a duration fails when the script loads, with the column and the nearest valid name. Parts the script does not describe (the fields of a
 * response, say) are {@link T#ANY} and are allowed to be whatever they turn out to be.
 */
public final class Checker {

    public enum T {
        BOOL("yes/no value", "a yes/no value"), NUM("number", "a number"), STR("text", "a text"), DUR("duration", "a duration"),
        RATIO("ratio", "a ratio"), CASE("case", "a case"), CASES("set of cases", "a set of cases"), GROUPS("grouped cases", "grouped cases"),
        LIST("list", "a list"), ANY("value", "a value");

        final String noun;
        final String phrase;

        T(String noun, String phrase) {
            this.noun = noun;
            this.phrase = phrase;
        }
    }

    private final Map<String, T> fields;
    private final Map<String, T> params;
    private final Map<String, Interpreter.Measure> measures;
    private final boolean allowHistory;
    private final boolean allowCase;
    private final Map<String, T> measureTypes = new LinkedHashMap<>();
    private final List<String> inProgress = new ArrayList<>();

    /**
     * @param fields       the fields of a case and their types (inputs and the built-in ones)
     * @param params       ladder parameters and their types
     * @param measures     the measures in scope
     * @param allowHistory whether {@code history} may be read (a ladder: yes; a measure body: no)
     * @param allowCase    whether {@code case} may be read (an {@code escalate} rule: yes)
     */
    public Checker(Map<String, T> fields, Map<String, T> params, Map<String, Interpreter.Measure> measures, boolean allowHistory, boolean allowCase) {
        this.fields = fields;
        this.params = params;
        this.measures = measures;
        this.allowHistory = allowHistory;
        this.allowCase = allowCase;
    }

    private record Scope(boolean perCase, Map<String, T> locals, boolean inMeasure) { }

    /** The type of the expression, or an {@link ExprException} saying what is wrong and where. */
    public T type(Expr e) {
        return type(e, new Scope(false, Map.of(), false));
    }

    /** As {@link #type}, and the expression must be a yes/no condition (or something not described, which could be). */
    public T condition(Expr e) {
        T t = type(e);
        if (t != T.BOOL && t != T.ANY) throw new ExprException("'" + e.show() + "' is not a yes/no condition (it is " + t.phrase + ")", e.column());
        return t;
    }

    /** The type of a measure's value, checking its body (and so detecting a measure that calls itself). */
    public T measureType(String name) {
        T known = measureTypes.get(name);
        if (known != null) return known;
        Interpreter.Measure m = measures.get(name);
        if (m == null) return T.ANY;
        if (inProgress.contains(name)) {
            List<String> chain = inProgress.subList(inProgress.indexOf(name), inProgress.size());
            String path = String.join(" → ", chain) + " → " + name;
            throw new ExprException("measure '" + name + "' calls itself" + (chain.size() > 1 ? " (" + path + ")" : ""), m.body().column());
        }
        inProgress.add(name);
        try {
            Map<String, T> locals = new LinkedHashMap<>();
            locals.put(m.param(), T.CASE);
            T t = type(m.body(), new Scope(true, locals, true));
            measureTypes.put(name, t);
            return t;
        } finally {
            inProgress.remove(inProgress.size() - 1);
        }
    }

    private Set<String> allNames(Scope s) {
        Set<String> names = new TreeSet<>(measures.keySet());
        names.addAll(params.keySet());
        names.addAll(s.locals().keySet());
        if (s.perCase()) names.addAll(fields.keySet());
        if (allowHistory && !s.inMeasure()) names.add("history");
        if (allowCase && !s.inMeasure()) names.add("case");
        return names;
    }

    private T type(Expr e, Scope s) {
        if (e instanceof Expr.Lit l) {
            V v = l.value();
            if (v instanceof V.Bool) return T.BOOL;
            if (v instanceof V.Str) return T.STR;
            if (v instanceof V.Dur) return T.DUR;
            return T.NUM;
        }
        if (e instanceof Expr.Name n) return name(n, s);
        if (e instanceof Expr.Field f) return field(type(f.target(), s), f, s);
        if (e instanceof Expr.Call c) return call(type(c.target(), s), c, s);
        if (e instanceof Expr.Fn f) return fn(f, s);
        if (e instanceof Expr.Bin b) return bin(b, s);
        if (e instanceof Expr.Not n) {
            T t = type(n.operand(), s);
            if (t != T.BOOL && t != T.ANY) throw new ExprException("'not' needs a yes/no value but got " + t.phrase + " at column " + n.column(), n.column());
            return T.BOOL;
        }
        if (e instanceof Expr.Neg n) {
            T t = type(n.operand(), s);
            if (t != T.NUM && t != T.DUR && t != T.ANY) throw new ExprException("cannot negate " + t.phrase + " at column " + n.column(), n.column());
            return t;
        }
        if (e instanceof Expr.ListLit l) {
            for (Expr x : l.items()) type(x, s);
            return T.LIST;
        }
        return T.ANY;
    }

    private T name(Expr.Name n, Scope s) {
        String name = n.name();
        T local = s.locals().get(name);
        if (local != null) return local;
        if (name.equals("history") && !s.inMeasure()) {
            if (allowHistory) return T.CASES;
        }
        if (name.equals("case") && !s.inMeasure()) {
            if (allowCase) return T.CASE;
        }
        if ((name.equals("history") || name.equals("case")) && s.inMeasure()) {
            throw new ExprException("measures cannot read '" + name + "': a measure is a function of one case (column " + n.column() + ")", n.column());
        }
        T param = params.get(name);
        if (param != null) return param;
        if (measures.containsKey(name)) {
            if (!s.perCase()) {
                throw new ExprException("'" + name + "' is a measure of one case: use it inside where(" + name + "), count(" + name + ") and the like, or write "
                        + name + "(some_case) (column " + n.column() + ")", n.column());
            }
            return measureType(name);
        }
        if (s.perCase() && fields.containsKey(name)) return fields.get(name);
        if (!s.perCase() && fields.containsKey(name)) {
            throw new ExprException("unknown name '" + name + "' here (it is a case field: use it inside where(...) or a measure, or write case." + name
                    + ") at column " + n.column(), n.column());
        }
        throw new ExprException("unknown name '" + name + "' at column " + n.column() + Suggest.hint(name, allNames(s)), n.column());
    }

    private T field(T target, Expr.Field f, Scope s) {
        String name = f.name();
        switch (target) {
            case ANY:
                return T.ANY;
            case CASE: {
                T t = fields.get(name);
                if (t != null) return t;
                throw new ExprException("a case has no field '" + name + "' at column " + f.column() + Suggest.hint(name, fields.keySet()), f.column());
            }
            case CASES:
                if (name.equals("count") || name.equals("n")) return T.NUM;
                if (name.equals("span")) return T.DUR;
                throw noField(target, f, Set.of("count", "n", "span"));
            case GROUPS: case LIST:
                if (name.equals("count")) return T.NUM;
                throw noField(target, f, Set.of("count"));
            case RATIO:
                if (Set.of("value", "n", "lower95", "upper95").contains(name)) return T.NUM;
                throw noField(target, f, Set.of("value", "n", "lower95", "upper95"));
            default:
                throw noField(target, f, Set.of());
        }
    }

    private static ExprException noField(T target, Expr.Field f, Set<String> known) {
        return new ExprException(target.phrase + " has no field '" + f.name() + "' at column " + f.column() + Suggest.hint(f.name(), known), f.column());
    }

    // ---------- methods ----------

    private static final Set<String> CASES_METHODS = Set.of("where", "last", "since", "between", "by", "count", "rate", "any", "all", "mean",
            "median", "min", "max", "sum", "quantile");
    private static final Set<String> GROUP_METHODS = Set.of("count", "rate", "any", "all", "mean", "median", "min", "max", "sum", "quantile");
    private static final Set<String> LIST_METHODS = Set.of("count", "sum", "min", "max", "mean");

    private T call(T target, Expr.Call c, Scope s) {
        String m = c.method();
        if (target == T.ANY) {
            c.args().forEach(a -> type(a, new Scope(true, s.locals(), s.inMeasure())));
            return T.ANY;
        }
        Set<String> methods = target == T.CASES ? CASES_METHODS : target == T.GROUPS ? GROUP_METHODS : target == T.LIST ? LIST_METHODS : Set.of();
        if (!methods.contains(m)) {
            throw new ExprException(target.phrase + " has no method '" + m + "' at column " + c.column() + Suggest.hint(m, methods), c.column());
        }
        List<Expr> args = c.args();
        Scope perCase = new Scope(true, s.locals(), s.inMeasure());
        switch (m) {
            case "where":
                arity(c, 1);
                predicate(c, args.get(0), perCase);
                return T.CASES;
            case "last":
                arity(c, 1);
                need(c, args.get(0), type(args.get(0), s), T.NUM, "a number");
                return T.CASES;
            case "since":
                arity(c, 1);
                need(c, args.get(0), type(args.get(0), s), T.DUR, "a duration");
                return T.CASES;
            case "between":
                arity(c, 2);
                need(c, args.get(0), type(args.get(0), s), T.DUR, "a duration");
                need(c, args.get(1), type(args.get(1), s), T.DUR, "a duration");
                return T.CASES;
            case "by":
                arity(c, 1);
                type(args.get(0), perCase);
                return T.GROUPS;
            case "count":
                if (target == T.LIST && args.isEmpty()) return T.NUM;
                if (args.size() > 1) throw new ExprException("'count' expects 0 or 1 argument at column " + c.column(), c.column());
                if (args.size() == 1) predicate(c, args.get(0), perCase);
                return target == T.GROUPS ? T.LIST : T.NUM;
            case "rate":
                arity(c, 1);
                predicate(c, args.get(0), perCase);
                return target == T.GROUPS ? T.LIST : T.RATIO;
            case "any": case "all":
                arity(c, 1);
                predicate(c, args.get(0), perCase);
                return target == T.GROUPS ? T.LIST : T.BOOL;
            case "quantile": {
                arity(c, 2);
                T v = type(args.get(0), perCase);
                numeric(c, args.get(0), v);
                T q = type(args.get(1), s);
                if (q != T.NUM) throw new ExprException("'quantile' expects a number between 0 and 1 as its second argument at column " + args.get(1).column(), args.get(1).column());
                return target == T.GROUPS ? T.LIST : v == T.DUR ? T.DUR : T.NUM;
            }
            default: {
                if (target == T.LIST) {
                    arity(c, 0);
                    return T.NUM;
                }
                arity(c, 1);
                T v = type(args.get(0), perCase);
                numeric(c, args.get(0), v);
                return target == T.GROUPS ? T.LIST : v == T.DUR ? T.DUR : T.NUM;
            }
        }
    }

    private static void arity(Expr.Call c, int n) {
        if (c.args().size() != n) {
            throw new ExprException("'" + c.method() + "' expects " + n + " argument" + (n == 1 ? "" : "s") + " but got " + c.args().size() + " at column "
                    + c.column(), c.column());
        }
    }

    private void predicate(Expr.Call c, Expr arg, Scope perCase) {
        T t = type(arg, perCase);
        if (t != T.BOOL && t != T.ANY) {
            throw new ExprException("'" + c.method() + "' expects a yes/no condition but '" + arg.show() + "' is " + t.phrase + " at column " + arg.column(), arg.column());
        }
    }

    private static void need(Expr.Call c, Expr arg, T actual, T wanted, String what) {
        if (actual != wanted && actual != T.ANY) {
            throw new ExprException("'" + c.method() + "' expects " + what + " but '" + arg.show() + "' is " + actual.phrase + " at column " + arg.column(), arg.column());
        }
    }

    private static void numeric(Expr.Call c, Expr arg, T t) {
        if (t != T.NUM && t != T.DUR && t != T.ANY) {
            throw new ExprException("'" + c.method() + "' expects a number or a duration but '" + arg.show() + "' is " + t.phrase + " at column " + arg.column(), arg.column());
        }
    }

    // ---------- functions and operators ----------

    private T fn(Expr.Fn f, Scope s) {
        List<T> args = new ArrayList<>();
        for (Expr a : f.args()) args.add(type(a, s));
        if (measures.containsKey(f.name())) {
            if (f.args().size() != 1) throw new ExprException("measure '" + f.name() + "' takes one case but got " + f.args().size() + " arguments at column " + f.column(), f.column());
            T a = args.get(0);
            if (a != T.CASE && a != T.ANY) throw new ExprException("measure '" + f.name() + "' takes a case but got " + a.phrase + " at column " + f.column(), f.column());
            return measureType(f.name());
        }
        switch (f.name()) {
            case "distance":
                if (f.args().size() != 2) throw new ExprException("'distance' expects 2 arguments at column " + f.column(), f.column());
                return T.NUM;
            case "abs":
                if (f.args().size() != 1) throw new ExprException("'abs' expects 1 argument at column " + f.column(), f.column());
                return T.NUM;
            default: {
                Set<String> known = new TreeSet<>(measures.keySet());
                known.add("distance");
                known.add("abs");
                throw new ExprException("unknown function '" + f.name() + "' at column " + f.column() + Suggest.hint(f.name(), known), f.column());
            }
        }
    }

    private T bin(Expr.Bin b, Scope s) {
        T l = type(b.left(), s);
        T r = type(b.right(), s);
        String op = b.op();
        switch (op) {
            case "and": case "or":
                if (l != T.BOOL && l != T.ANY) throw new ExprException("'" + op + "' needs yes/no values but the left side is " + l.phrase + " at column " + b.column(), b.column());
                if (r != T.BOOL && r != T.ANY) throw new ExprException("'" + op + "' needs yes/no values but the right side is " + r.phrase + " at column " + b.column(), b.column());
                return T.BOOL;
            case "in":
                if (r != T.LIST && r != T.ANY) throw new ExprException("'in' needs a list on the right at column " + b.column(), b.column());
                return T.BOOL;
            case "+": case "-": case "*": case "/":
                return arithmetic(b, l, r);
            case "==": case "!=":
                comparable(b, l, r);
                return T.BOOL;
            default:
                comparable(b, l, r);
                if (l == T.BOOL || r == T.BOOL) throw new ExprException("cannot order yes/no values at column " + b.column(), b.column());
                return T.BOOL;
        }
    }

    private static boolean numberLike(T t) { return t == T.NUM || t == T.RATIO; }

    private static void comparable(Expr.Bin b, T l, T r) {
        if (l == T.ANY || r == T.ANY) return;
        if (numberLike(l) && numberLike(r)) return;
        if (l == r && (l == T.DUR || l == T.STR || l == T.BOOL)) return;
        throw new ExprException("cannot compare " + l.phrase + " with " + r.phrase + " at column " + b.column(), b.column());
    }

    private static T arithmetic(Expr.Bin b, T l, T r) {
        String op = b.op();
        if (l == T.ANY || r == T.ANY) return l == T.DUR || r == T.DUR ? T.DUR : T.NUM;
        if (numberLike(l) && numberLike(r)) return T.NUM;
        if (l == T.DUR && r == T.DUR && (op.equals("+") || op.equals("-"))) return T.DUR;
        if (l == T.DUR && numberLike(r) && (op.equals("*") || op.equals("/"))) return T.DUR;
        if (numberLike(l) && r == T.DUR && op.equals("*")) return T.DUR;
        String verb = switch (op) {
            case "+" -> "add";
            case "-" -> "subtract";
            case "*" -> "multiply";
            default -> "divide";
        };
        throw new ExprException("cannot " + verb + " " + l.phrase + " and " + r.phrase + " at column " + b.column(), b.column());
    }
}
