package io.github.llm4j.loom.autonomy.expr;

import java.util.List;
import java.util.stream.Collectors;

/** The parsed form of a ladder expression. Every node remembers its column so a message can point at it. */
public sealed interface Expr {

    int column();

    /** The canonical text of the expression; parsing it gives the same shape. */
    String show();

    record Lit(V value, String text, int column) implements Expr {
        public String show() { return text; }
    }

    record Name(String name, int column) implements Expr {
        public String show() { return name; }
    }

    /** {@code target.name} */
    record Field(Expr target, String name, int column) implements Expr {
        public String show() { return target.show() + "." + name; }
    }

    /** {@code target.method(args)} */
    record Call(Expr target, String method, List<Expr> args, int column) implements Expr {
        public String show() { return target.show() + "." + method + "(" + join(args) + ")"; }
    }

    /** {@code name(args)}: a measure or a built-in function. */
    record Fn(String name, List<Expr> args, int column) implements Expr {
        public String show() { return name + "(" + join(args) + ")"; }
    }

    record Bin(String op, Expr left, Expr right, int column) implements Expr {
        public String show() { return "(" + left.show() + " " + op + " " + right.show() + ")"; }
    }

    record Not(Expr operand, int column) implements Expr {
        public String show() { return "(not " + operand.show() + ")"; }
    }

    record Neg(Expr operand, int column) implements Expr {
        public String show() { return "(-" + operand.show() + ")"; }
    }

    record ListLit(List<Expr> items, int column) implements Expr {
        public String show() { return "[" + join(items) + "]"; }
    }

    private static String join(List<Expr> xs) {
        return xs.stream().map(Expr::show).collect(Collectors.joining(", "));
    }
}
