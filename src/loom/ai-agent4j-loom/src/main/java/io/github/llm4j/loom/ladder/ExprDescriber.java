package io.github.llm4j.loom.ladder;

import io.github.llm4j.loom.autonomy.expr.Expr;
import io.github.llm4j.loom.autonomy.expr.V;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A condition of a ladder as an English sentence, built from the parsed condition and not from its
 * text: a risk owner can read what the rules say, and what is shown is what is enforced. Anything
 * it has no phrase for is shown as written.
 */
public final class ExprDescriber {

    private ExprDescriber() {}

    public static String describe(Expr e) {
        return phrase(e, false);
    }

    private static String words(String name) {
        return name.replace('_', ' ');
    }

    private static String duration(long ms) {
        long day = 86_400_000L, hour = 3_600_000L, minute = 60_000L, second = 1000L;
        if (ms != 0 && ms % day == 0) return unit(ms / day, "day");
        if (ms != 0 && ms % hour == 0) return unit(ms / hour, "hour");
        if (ms != 0 && ms % minute == 0) return unit(ms / minute, "minute");
        if (ms != 0 && ms % second == 0) return unit(ms / second, "second");
        return ms + " milliseconds";
    }

    private static String unit(long n, String name) {
        return n + " " + name + (n == 1 ? "" : "s");
    }

    private static String number(double d) {
        return Show.number(d);
    }

    private static String percent(double d) {
        return Show.number(d * 100) + "%";
    }

    private static String op(String op) {
        switch (op) {
            case ">=":
                return "is at least";
            case ">":
                return "is more than";
            case "<=":
                return "is at most";
            case "<":
                return "is less than";
            case "==":
                return "is";
            case "!=":
                return "is not";
            default:
                return op;
        }
    }

    private static boolean comparison(String op) {
        return op.equals(">=")
                || op.equals(">")
                || op.equals("<=")
                || op.equals("<")
                || op.equals("==")
                || op.equals("!=");
    }

    /**
     * True when the expression is a share (a rate or one of its figures), which reads better as a
     * percentage.
     */
    private static boolean ratio(Expr e) {
        if (e instanceof Expr.Field f)
            return f.name().equals("lower95")
                            || f.name().equals("upper95")
                            || f.name().equals("value")
                    ? ratio(f.target())
                    : false;
        if (e instanceof Expr.Call c) {
            if (c.method().equals("rate")) return true;
            return (c.method().equals("min") || c.method().equals("max"))
                    && c.target() instanceof Expr.Call inner
                    && inner.method().equals("rate");
        }
        return false;
    }

    private static String phrase(Expr e, boolean nested) {
        if (e instanceof Expr.Bin b) {
            if (comparison(b.op())) {
                String left = phrase(b.left(), true);
                String right =
                        b.right() instanceof Expr.Lit l
                                        && l.value() instanceof V.Num n
                                        && ratio(b.left())
                                        && !l.text().endsWith("%")
                                ? percent(n.value())
                                : phrase(b.right(), true);
                return left + " " + op(b.op()) + " " + right;
            }
            if (b.op().equals("and") || b.op().equals("or")) {
                String text =
                        phrase(b.left(), false) + ", " + b.op() + " " + phrase(b.right(), false);
                return nested ? "(" + text + ")" : text;
            }
            if (b.op().equals("in"))
                return phrase(b.left(), true) + " is one of " + listItems(b.right());
            String verb =
                    b.op().equals("*")
                            ? " times "
                            : b.op().equals("/")
                                    ? " divided by "
                                    : b.op().equals("+") ? " plus " : " minus ";
            return phrase(b.left(), true) + verb + phrase(b.right(), true);
        }
        if (e instanceof Expr.Not n) return "not " + phrase(n.operand(), true);
        if (e instanceof Expr.Neg n) return "minus " + phrase(n.operand(), true);
        if (e instanceof Expr.Lit l) {
            V v = l.value();
            if (v instanceof V.Dur d) return duration(d.millis());
            if (v instanceof V.Num n) return l.text().endsWith("%") ? l.text() : number(n.value());
            return l.show();
        }
        if (e instanceof Expr.Name n) return words(n.name());
        if (e instanceof Expr.ListLit l) return listItems(l);
        if (e instanceof Expr.Fn f) {
            if (f.name().equals("distance") && f.args().size() == 2)
                return "how far "
                        + phrase(f.args().get(0), true)
                        + " is from "
                        + phrase(f.args().get(1), true);
            if (f.args().size() == 1 && f.args().get(0) instanceof Expr.Name)
                return words(f.name());
            return f.show();
        }
        if (e instanceof Expr.Field f) return field(f);
        if (e instanceof Expr.Call c) return call(c);
        return e.show();
    }

    private static String listItems(Expr e) {
        if (e instanceof Expr.ListLit l)
            return l.items().stream().map(x -> phrase(x, true)).collect(Collectors.joining(", "));
        return phrase(e, true);
    }

    private static String field(Expr.Field f) {
        if (f.target() instanceof Expr.Name n && n.name().equals("case"))
            return "the case's " + words(f.name());
        if (f.target() instanceof Expr.Name n && n.name().equals("history")) {
            switch (f.name()) {
                case "count":
                case "n":
                    return "the number of cases";
                case "span":
                    return "the time the cases span";
                case "age":
                    return "how long ago the first case was";
                default:
                    break;
            }
        }
        if (f.target() instanceof Expr.Call c && c.target() != null) {
            String inner = call(c);
            switch (f.name()) {
                case "lower95":
                    return "a cautious estimate of " + inner;
                case "upper95":
                    return "an optimistic estimate of " + inner;
                case "value":
                    return inner;
                case "n":
                    return "the number of cases behind " + inner;
                case "count":
                    return countOf(setOf(c));
                case "span":
                    return "the time the " + setOf(c) + " span";
                default:
                    break;
            }
        }
        if (f.name().equals("count") || f.name().equals("n")) return countOf(setOf(f.target()));
        if (f.name().equals("span")) return "the time the " + setOf(f.target()) + " span";
        if (f.name().equals("age"))
            return "how long ago the first of the " + setOf(f.target()) + " was";
        return f.show();
    }

    private static String countOf(String set) {
        return "the number of " + set;
    }

    /**
     * The set of cases a chain ends in: {@code cases}, {@code the last 50 cases}, {@code cases
     * where careful}.
     */
    private static String setOf(Expr e) {
        if (e instanceof Expr.Name n && n.name().equals("history")) return "cases";
        if (e instanceof Expr.Call c) {
            String base = setOf(c.target());
            switch (c.method()) {
                case "last":
                    return "the last " + phrase(c.args().get(0), true) + " cases";
                case "since":
                    return "cases of the last " + phrase(c.args().get(0), true);
                case "between":
                    return "cases between "
                            + phrase(c.args().get(0), true)
                            + " and "
                            + phrase(c.args().get(1), true)
                            + " ago";
                case "where":
                    {
                        String cond = phrase(c.args().get(0), true);
                        if (base.startsWith("cases where")) return base + ", where " + cond;
                        return base + " where " + cond;
                    }
                default:
                    return base;
            }
        }
        return phrase(e, true);
    }

    private static String call(Expr.Call c) {
        String text = callPlain(c);
        if (c.target() instanceof Expr.Call by
                && by.method().equals("by")
                && !by.args().isEmpty()
                && !c.method().equals("min")
                && !c.method().equals("max")
                && !c.method().equals("by")) {
            return text + " in each " + phrase(by.args().get(0), true);
        }
        return text;
    }

    private static String callPlain(Expr.Call c) {
        String set = setOf(c.target());
        List<Expr> a = c.args();
        switch (c.method()) {
            case "count":
                if (a.isEmpty()) return "the number of " + set;
                return "the number of "
                        + set
                        + (set.contains(" where ") ? ", where " : " where ")
                        + phrase(a.get(0), true);
            case "rate":
                return "the share of "
                        + set
                        + (set.contains(" where ") ? ", where " : " where ")
                        + phrase(a.get(0), true);
            case "mean":
                return "the average " + phrase(a.get(0), true) + " of " + set;
            case "median":
                return "the median " + phrase(a.get(0), true) + " of " + set;
            case "min":
            case "max":
                if (a.isEmpty()
                        && c.target() instanceof Expr.Call inner
                        && inner.target() instanceof Expr.Call by
                        && by.method().equals("by")) {
                    return "the "
                            + (c.method().equals("min") ? "lowest" : "highest")
                            + ", over each "
                            + phrase(by.args().get(0), true)
                            + ", of "
                            + callPlain(inner);
                }
                return "the "
                        + (c.method().equals("min") ? "lowest " : "highest ")
                        + phrase(a.get(0), true)
                        + " of "
                        + set;
            case "sum":
                return "the total " + phrase(a.get(0), true) + " of " + set;
            case "quantile":
                return "the "
                        + phrase(a.get(1), true)
                        + " quantile of "
                        + phrase(a.get(0), true)
                        + " over "
                        + set;
            case "any":
                return "any case"
                        + (set.equals("cases") ? "" : " among " + set)
                        + " where "
                        + phrase(a.get(0), true);
            case "all":
                return "every case"
                        + (set.equals("cases") ? "" : " among " + set)
                        + " where "
                        + phrase(a.get(0), true);
            case "by":
                return set + " grouped by " + phrase(a.get(0), true);
            default:
                return c.show();
        }
    }
}
