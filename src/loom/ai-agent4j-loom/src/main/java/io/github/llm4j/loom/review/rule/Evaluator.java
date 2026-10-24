package io.github.llm4j.loom.review.rule;

import io.github.llm4j.loom.review.fact.Entity;
import io.github.llm4j.loom.review.fact.FactBase;
import io.github.llm4j.loom.review.fact.FactModel;
import io.github.llm4j.loom.review.fact.Unknown;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Evaluates a condition on an entity. Three-valued: a leaf that reads a fact that is unknown is
 * unknown, never false, so a rule cannot fire (or stay quiet) on something nobody knows. The
 * vocabulary has no loops or calls, and a regular expression is cut off after a fixed number of
 * steps, so evaluation always finishes.
 */
public final class Evaluator {

    /** The most characters a regular expression may examine in one test. */
    static final int REGEX_STEPS = 100_000;

    private Evaluator() {}

    public static Evaluation evaluate(Condition c, Entity root, FactBase facts) {
        if (c instanceof Condition.All a) {
            List<Evaluation> parts = new ArrayList<>();
            Tri result = Tri.TRUE;
            for (Condition part : a.all()) {
                Evaluation e = evaluate(part, root, facts);
                parts.add(e);
                if (e.result() == Tri.FALSE) result = Tri.FALSE;
                else if (e.result() == Tri.UNKNOWN && result == Tri.TRUE) result = Tri.UNKNOWN;
            }
            return new Evaluation(result, "all of", parts);
        }
        if (c instanceof Condition.Any a) {
            List<Evaluation> parts = new ArrayList<>();
            Tri result = Tri.FALSE;
            for (Condition part : a.any()) {
                Evaluation e = evaluate(part, root, facts);
                parts.add(e);
                if (e.result() == Tri.TRUE) result = Tri.TRUE;
                else if (e.result() == Tri.UNKNOWN && result == Tri.FALSE) result = Tri.UNKNOWN;
            }
            return new Evaluation(result, "any of", parts);
        }
        if (c instanceof Condition.Not n) {
            Evaluation inner = evaluate(n.not(), root, facts);
            return new Evaluation(inner.result().not(), "not", List.of(inner));
        }
        return leaf((Condition.Leaf) c, root, facts);
    }

    private static Evaluation leaf(Condition.Leaf l, Entity root, FactBase facts) {
        Object value = read(root, l.path(), facts);
        String head = l.path() + " " + l.op().key();
        if (value == Unknown.INSTANCE)
            return new Evaluation(Tri.UNKNOWN, head + ": not known", List.of());
        switch (l.op()) {
            case EXISTS:
                return new Evaluation(
                        Tri.of(present(value)), head + ": saw " + Template.show(value), List.of());
            case MISSING:
                return new Evaluation(
                        Tri.of(!present(value)), head + ": saw " + Template.show(value), List.of());
            case COUNT:
                {
                    Operand.Count n = (Operand.Count) l.operand();
                    if (!(value instanceof List<?> list))
                        return new Evaluation(Tri.UNKNOWN, head + ": not a list", List.of());
                    return new Evaluation(
                            Tri.of(compare(n.comparison(), list.size(), n.bound())),
                            head
                                    + " "
                                    + n.comparison().key()
                                    + " "
                                    + n.bound()
                                    + ": saw "
                                    + list.size(),
                            List.of());
                }
            case SOME:
            case EVERY:
            case NONE:
                return quantified(l, value, head, facts);
            default:
                break;
        }
        Object other = operand(l.operand(), root, facts);
        String shown = head + " " + describe(l.operand()) + ": saw " + Template.show(value);
        if (other == Unknown.INSTANCE)
            return new Evaluation(Tri.UNKNOWN, shown + " (the other side is not known)", List.of());
        return new Evaluation(test(l.op(), value, other), shown, List.of());
    }

    private static Evaluation quantified(
            Condition.Leaf l, Object value, String head, FactBase facts) {
        if (!(value instanceof List<?> list))
            return new Evaluation(Tri.UNKNOWN, head + ": not a list", List.of());
        List<Evaluation> kids = new ArrayList<>();
        boolean anyTrue = false;
        boolean anyFalse = false;
        boolean anyUnknown = false;
        for (Object o : list) {
            if (!(o instanceof Entity e)) continue;
            Evaluation k = evaluate(l.nested(), e, facts);
            kids.add(k);
            anyTrue |= k.result() == Tri.TRUE;
            anyFalse |= k.result() == Tri.FALSE;
            anyUnknown |= k.result() == Tri.UNKNOWN;
        }
        Tri some = anyTrue ? Tri.TRUE : anyUnknown ? Tri.UNKNOWN : Tri.FALSE;
        Tri result =
                switch (l.op()) {
                    case SOME -> some;
                    case NONE -> some.not();
                    default -> anyFalse ? Tri.FALSE : anyUnknown ? Tri.UNKNOWN : Tri.TRUE;
                };
        return new Evaluation(result, head + " (" + list.size() + " elements)", kids);
    }

    // ---- reading facts

    /** The fact a dotted path reaches from {@code root}, or unknown when any step of it is. */
    public static Object read(Entity root, String path, FactBase facts) {
        if (path.startsWith(FactModel.SETTING + "."))
            return facts.setting(path.substring(FactModel.SETTING.length() + 1));
        Object cur = root;
        for (String part : path.split("\\.")) {
            if (!(cur instanceof Entity e)) return Unknown.INSTANCE;
            cur = e.get(part);
            if (cur == Unknown.INSTANCE) return cur;
        }
        return cur;
    }

    private static Object operand(Operand o, Entity root, FactBase facts) {
        if (o instanceof Operand.Literal l) return l.value();
        if (o instanceof Operand.Literals l) return l.values();
        if (o instanceof Operand.Setting s) return facts.setting(s.key());
        if (o instanceof Operand.Field f) return read(root, f.path(), facts);
        return Unknown.INSTANCE;
    }

    private static String describe(Operand o) {
        if (o instanceof Operand.Literal l) return String.valueOf(l.value());
        if (o instanceof Operand.Literals l) return String.valueOf(l.values());
        if (o instanceof Operand.Setting s) return "setting." + s.key();
        if (o instanceof Operand.Field f) return "field " + f.path();
        return "";
    }

    private static boolean present(Object v) {
        if (v instanceof String s) return !s.isEmpty();
        if (v instanceof List<?> l) return !l.isEmpty();
        return true;
    }

    // ---- comparing

    private static Tri test(Op op, Object value, Object other) {
        switch (op) {
            case IS:
                return Tri.of(same(value, other));
            case IS_NOT:
                return Tri.of(!same(value, other));
            case IN:
                return Tri.of(members(other).stream().anyMatch(m -> same(value, m)));
            case NOT_IN:
                return Tri.of(members(other).stream().noneMatch(m -> same(value, m)));
            case GT:
            case GTE:
            case LT:
            case LTE:
                if (!(value instanceof Number a) || number(other) == null) return Tri.UNKNOWN;
                return Tri.of(compare(op, a.doubleValue(), number(other)));
            case CONTAINS:
                if (value instanceof List<?> l)
                    return Tri.of(l.stream().anyMatch(x -> same(x, other)));
                return Tri.of(String.valueOf(value).contains(String.valueOf(other)));
            case STARTS_WITH:
                return Tri.of(String.valueOf(value).startsWith(String.valueOf(other)));
            case MATCHES:
                return matches(String.valueOf(value), String.valueOf(other));
            default:
                return Tri.UNKNOWN;
        }
    }

    private static boolean same(Object a, Object b) {
        if (a instanceof Number x && number(b) != null) return x.doubleValue() == number(b);
        if (a instanceof Boolean && b instanceof String s) return String.valueOf(a).equals(s);
        return String.valueOf(a).equals(String.valueOf(b));
    }

    private static Double number(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** The words of a list operand; a setting holds them as one comma-separated string. */
    private static List<Object> members(Object other) {
        if (other instanceof List<?> l) return new ArrayList<>(l);
        List<Object> out = new ArrayList<>();
        for (String part : String.valueOf(other).split(","))
            if (!part.isBlank()) out.add(part.trim());
        return out;
    }

    private static boolean compare(Op op, double a, double b) {
        return switch (op) {
            case GT -> a > b;
            case GTE -> a >= b;
            case LT -> a < b;
            case LTE -> a <= b;
            case IS -> a == b;
            case IS_NOT -> a != b;
            default -> false;
        };
    }

    private static Tri matches(String text, String regex) {
        try {
            return Tri.of(Pattern.compile(regex).matcher(new Bounded(text)).find());
        } catch (TooManySteps e) {
            return Tri.UNKNOWN;
        } catch (java.util.regex.PatternSyntaxException e) {
            return Tri.UNKNOWN;
        }
    }

    /** Thrown when a regular expression has looked at more characters than it is allowed to. */
    private static final class TooManySteps extends RuntimeException {
        TooManySteps() {
            super(null, null, false, false);
        }
    }

    /**
     * A character sequence that stops a runaway regular expression after {@link #REGEX_STEPS}
     * reads.
     */
    private static final class Bounded implements CharSequence {
        private final CharSequence inner;
        private int steps;

        Bounded(CharSequence inner) {
            this.inner = inner;
        }

        @Override
        public int length() {
            return inner.length();
        }

        @Override
        public char charAt(int index) {
            if (++steps > REGEX_STEPS) throw new TooManySteps();
            return inner.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new Bounded(inner.subSequence(start, end));
        }

        @Override
        public String toString() {
            return inner.toString();
        }
    }
}
