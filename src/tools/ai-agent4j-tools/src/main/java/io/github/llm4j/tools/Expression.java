package io.github.llm4j.tools;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Exact decimal arithmetic for {@code compute}'s {@code calc} action: numbers, {@code + - * / %},
 * parentheses, unary minus, and {@code round}, {@code min}, {@code max}, {@code abs}. A recursive
 * descent parser over {@link BigDecimal}; no scripting engine. Every input is bounded (length,
 * nesting, digits of a number and of a result), and anything it cannot read is refused with the
 * reason.
 */
final class Expression {

    static final int MAX_LENGTH = 1000;
    static final int MAX_DEPTH = 32;
    static final int MAX_NUMBER_DIGITS = 100;
    static final int MAX_RESULT_DIGITS = 400;
    static final int MAX_SCALE = 100;

    /** A division that does not end is rounded to this many significant digits. */
    static final MathContext DIVISION = MathContext.DECIMAL128;

    /** The value, and whether a division had to be rounded. */
    record Result(BigDecimal value, boolean rounded) {}

    private final String text;
    private int pos;
    private int depth;
    private boolean rounded;

    private Expression(String text) {
        this.text = text;
    }

    /** Evaluates {@code text}; throws {@link ToolRefusal} saying what is wrong. */
    static Result evaluate(String text) {
        if (text.length() > MAX_LENGTH)
            throw new ToolRefusal(
                    "calc: the expression is longer than " + MAX_LENGTH + " characters");
        Expression e = new Expression(text);
        BigDecimal value = e.sum();
        e.skipSpaces();
        if (e.pos < text.length()) throw e.unexpected();
        return new Result(value, e.rounded);
    }

    private BigDecimal sum() {
        BigDecimal value = product();
        while (true) {
            if (accept('+')) value = bounded(value.add(product()));
            else if (accept('-')) value = bounded(value.subtract(product()));
            else return value;
        }
    }

    private BigDecimal product() {
        BigDecimal value = unary();
        while (true) {
            if (accept('*')) value = bounded(value.multiply(unary()));
            else if (accept('/')) value = divide(value, unary());
            else if (accept('%')) {
                BigDecimal by = unary();
                if (by.signum() == 0) throw new ToolRefusal("calc: division by zero");
                value = bounded(value.remainder(by));
            } else return value;
        }
    }

    private BigDecimal divide(BigDecimal a, BigDecimal b) {
        if (b.signum() == 0) throw new ToolRefusal("calc: division by zero");
        try {
            return bounded(a.divide(b));
        } catch (ArithmeticException notExact) {
            rounded = true;
            return bounded(a.divide(b, DIVISION));
        }
    }

    private BigDecimal unary() {
        if (accept('-')) return bounded(unary().negate());
        if (accept('+')) return unary();
        return primary();
    }

    private BigDecimal primary() {
        skipSpaces();
        if (pos >= text.length()) throw new ToolRefusal("calc: the expression ends too early");
        char c = text.charAt(pos);
        if (c == '(') {
            pos++;
            enter();
            BigDecimal inner = sum();
            if (!accept(')')) throw new ToolRefusal("calc: missing ) at position " + (pos + 1));
            depth--;
            return inner;
        }
        if (Character.isDigit(c) || c == '.') return number();
        if (Character.isLetter(c)) return function();
        throw unexpected();
    }

    private BigDecimal number() {
        int start = pos;
        while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
        if (pos < text.length() && text.charAt(pos) == '.') {
            pos++;
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
        }
        String digits = text.substring(start, pos);
        if (digits.replace(".", "").length() > MAX_NUMBER_DIGITS)
            throw new ToolRefusal("calc: a number has more than " + MAX_NUMBER_DIGITS + " digits");
        try {
            return new BigDecimal(digits);
        } catch (NumberFormatException e) {
            pos = start;
            throw unexpected();
        }
    }

    private BigDecimal function() {
        int start = pos;
        while (pos < text.length() && Character.isLetter(text.charAt(pos))) pos++;
        String name = text.substring(start, pos);
        if (!accept('('))
            throw new ToolRefusal(
                    "calc: unknown name " + name + "; it knows round, min, max and abs");
        enter();
        List<BigDecimal> args = new ArrayList<>();
        if (!accept(')')) {
            do args.add(sum());
            while (accept(','));
            if (!accept(')'))
                throw new ToolRefusal("calc: missing ) after the arguments of " + name);
        }
        depth--;
        return switch (name) {
            case "round" -> round(args);
            case "min" -> extreme(name, args, true);
            case "max" -> extreme(name, args, false);
            case "abs" -> {
                if (args.size() != 1) throw new ToolRefusal("calc: abs takes one number");
                yield args.get(0).abs();
            }
            default -> throw new ToolRefusal(
                    "calc: unknown function " + name + "; it knows round, min, max and abs");
        };
    }

    private static BigDecimal round(List<BigDecimal> args) {
        if (args.isEmpty() || args.size() > 2)
            throw new ToolRefusal(
                    "calc: round takes one or two numbers: round(x) or round(x, places)");
        int places = 0;
        if (args.size() == 2) {
            BigDecimal p = args.get(1);
            if (p.stripTrailingZeros().scale() > 0
                    || p.compareTo(BigDecimal.ZERO) < 0
                    || p.compareTo(BigDecimal.valueOf(MAX_SCALE)) > 0)
                throw new ToolRefusal(
                        "calc: round's places must be a whole number from 0 to " + MAX_SCALE);
            places = p.intValueExact();
        }
        return args.get(0).setScale(places, RoundingMode.HALF_UP);
    }

    private static BigDecimal extreme(String name, List<BigDecimal> args, boolean min) {
        if (args.isEmpty()) throw new ToolRefusal("calc: " + name + " takes at least one number");
        BigDecimal best = args.get(0);
        for (BigDecimal v : args) if (min ? v.compareTo(best) < 0 : v.compareTo(best) > 0) best = v;
        return best;
    }

    private void enter() {
        if (++depth > MAX_DEPTH)
            throw new ToolRefusal(
                    "calc: the expression is nested more than " + MAX_DEPTH + " deep");
    }

    private static BigDecimal bounded(BigDecimal value) {
        if (value.precision() > MAX_RESULT_DIGITS || Math.abs(value.scale()) > MAX_RESULT_DIGITS)
            throw new ToolRefusal("calc: a result has more than " + MAX_RESULT_DIGITS + " digits");
        return value;
    }

    private boolean accept(char c) {
        skipSpaces();
        if (pos < text.length() && text.charAt(pos) == c) {
            pos++;
            return true;
        }
        return false;
    }

    private void skipSpaces() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
    }

    private ToolRefusal unexpected() {
        String rest = text.substring(pos);
        String shown = rest.length() > 10 ? rest.substring(0, 10) + "…" : rest;
        return new ToolRefusal("calc: unexpected '" + shown + "' at position " + (pos + 1));
    }
}
