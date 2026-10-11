package io.github.llm4j.loom.autonomy.expr;

import io.github.llm4j.loom.autonomy.expr.Lexer.Kind;
import io.github.llm4j.loom.autonomy.expr.Lexer.Token;
import java.util.ArrayList;
import java.util.List;

/** Parses a ladder expression (see the grammar in the loom-ladders design). Recursive descent; nesting is bounded. */
public final class ExprParser {

    private static final int MAX_DEPTH = 64;

    private final String source;
    private final List<Token> tokens;
    private int pos;
    private int depth;

    private ExprParser(String source) {
        this.source = source;
        this.tokens = Lexer.lex(source);
    }

    public static Expr parse(String source) {
        if (source == null || source.isBlank()) throw new ExprException("empty expression", 1);
        ExprParser p = new ExprParser(source);
        Expr e = p.or();
        Token t = p.peek();
        if (t.kind() != Kind.EOF) throw new ExprException("unexpected '" + t.text() + "' at column " + t.column(), t.column());
        return e;
    }

    private Token peek() { return tokens.get(pos); }

    private Token next() { return tokens.get(pos++); }

    private boolean at(Kind k) { return peek().kind() == k; }

    private Token expect(Kind k, String what) {
        Token t = peek();
        if (t.kind() != k) throw new ExprException("expected " + what + " at column " + t.column(), t.column());
        return next();
    }

    private void enter(Token t) {
        if (++depth > MAX_DEPTH) throw new ExprException("expression nested too deeply at column " + t.column(), t.column());
    }

    private Expr or() {
        Expr left = and();
        while (at(Kind.OR)) {
            Token op = next();
            left = new Expr.Bin("or", left, and(), op.column());
        }
        return left;
    }

    private Expr and() {
        Expr left = not();
        while (at(Kind.AND)) {
            Token op = next();
            left = new Expr.Bin("and", left, not(), op.column());
        }
        return left;
    }

    private Expr not() {
        if (at(Kind.NOT)) {
            Token op = next();
            enter(op);
            try {
                return new Expr.Not(not(), op.column());
            } finally {
                depth--;
            }
        }
        return comparison();
    }

    private Expr comparison() {
        Expr left = sum();
        Token t = peek();
        String op = switch (t.kind()) {
            case EQ -> "==";
            case NE -> "!=";
            case LT -> "<";
            case LE -> "<=";
            case GT -> ">";
            case GE -> ">=";
            case IN -> "in";
            default -> null;
        };
        if (op == null) return left;
        next();
        Expr right = sum();
        Token after = peek();
        if (after.kind() == Kind.EQ || after.kind() == Kind.NE || after.kind() == Kind.LT || after.kind() == Kind.LE
                || after.kind() == Kind.GT || after.kind() == Kind.GE || after.kind() == Kind.IN) {
            throw new ExprException("comparisons do not chain: use 'and' (column " + after.column() + ")", after.column());
        }
        return new Expr.Bin(op, left, right, t.column());
    }

    private Expr sum() {
        Expr left = term();
        while (at(Kind.PLUS) || at(Kind.MINUS)) {
            Token op = next();
            left = new Expr.Bin(op.text(), left, term(), op.column());
        }
        return left;
    }

    private Expr term() {
        Expr left = unary();
        while (at(Kind.STAR) || at(Kind.SLASH)) {
            Token op = next();
            left = new Expr.Bin(op.text(), left, unary(), op.column());
        }
        return left;
    }

    private Expr unary() {
        if (at(Kind.MINUS)) {
            Token op = next();
            enter(op);
            try {
                return new Expr.Neg(unary(), op.column());
            } finally {
                depth--;
            }
        }
        return postfix();
    }

    private Expr postfix() {
        Expr e = primary();
        while (at(Kind.DOT)) {
            Token dot = next();
            if (!at(Kind.IDENT)) throw new ExprException("expected a name after '.' at column " + peek().column(), peek().column());
            Token name = next();
            if (at(Kind.LPAREN)) {
                next();
                e = new Expr.Call(e, name.text(), args(), name.column());
            } else {
                e = new Expr.Field(e, name.text(), name.column());
            }
        }
        return e;
    }

    /** After '(' : the arguments up to and including ')'. */
    private List<Expr> args() {
        List<Expr> out = new ArrayList<>();
        if (at(Kind.RPAREN)) {
            next();
            return out;
        }
        while (true) {
            out.add(or());
            if (at(Kind.COMMA)) {
                next();
                continue;
            }
            expect(Kind.RPAREN, "')'");
            return out;
        }
    }

    private Expr primary() {
        Token t = peek();
        switch (t.kind()) {
            case NUMBER -> { next(); return new Expr.Lit(V.num(t.number()), trim(t.number()), t.column()); }
            case PERCENT -> { next(); return new Expr.Lit(V.num(t.number()), t.text(), t.column()); }
            case DURATION -> { next(); return new Expr.Lit(V.dur(t.millis()), t.text(), t.column()); }
            case STRING -> { next(); return new Expr.Lit(V.str(t.text()), "\"" + t.text().replace("\\", "\\\\").replace("\"", "\\\"") + "\"", t.column()); }
            case TRUE -> { next(); return new Expr.Lit(V.bool(true), "true", t.column()); }
            case FALSE -> { next(); return new Expr.Lit(V.bool(false), "false", t.column()); }
            case IDENT -> {
                next();
                if (at(Kind.LPAREN)) {
                    next();
                    return new Expr.Fn(t.text(), args(), t.column());
                }
                return new Expr.Name(t.text(), t.column());
            }
            case LPAREN -> {
                next();
                enter(t);
                try {
                    Expr e = or();
                    expect(Kind.RPAREN, "')'");
                    return e;
                } finally {
                    depth--;
                }
            }
            case LBRACKET -> {
                next();
                List<Expr> items = new ArrayList<>();
                if (at(Kind.RBRACKET)) {
                    next();
                    return new Expr.ListLit(items, t.column());
                }
                while (true) {
                    items.add(or());
                    if (at(Kind.COMMA)) {
                        next();
                        continue;
                    }
                    expect(Kind.RBRACKET, "']'");
                    return new Expr.ListLit(items, t.column());
                }
            }
            default -> throw new ExprException("expected a value at column " + t.column() + " but found "
                    + (t.kind() == Kind.EOF ? "the end of the expression" : "'" + t.text() + "'"), t.column());
        }
    }

    private static String trim(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
    }
}
