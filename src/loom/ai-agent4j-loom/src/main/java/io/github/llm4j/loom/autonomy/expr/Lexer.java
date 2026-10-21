package io.github.llm4j.loom.autonomy.expr;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the text of a ladder expression into tokens. Numbers may carry a unit: {@code 90%}, {@code
 * 14d}, {@code 30s}.
 */
public final class Lexer {

    public enum Kind {
        NUMBER,
        PERCENT,
        DURATION,
        STRING,
        IDENT,
        TRUE,
        FALSE,
        AND,
        OR,
        NOT,
        IN,
        DOT,
        COMMA,
        LPAREN,
        RPAREN,
        LBRACKET,
        RBRACKET,
        EQ,
        NE,
        LT,
        LE,
        GT,
        GE,
        PLUS,
        MINUS,
        STAR,
        SLASH,
        EOF
    }

    /**
     * A token. {@code number} is set for NUMBER and PERCENT (a percentage is already divided by
     * 100); {@code millis} for DURATION.
     */
    public record Token(Kind kind, String text, double number, long millis, int column) {}

    private Lexer() {}

    public static List<Token> lex(String s) {
        List<Token> out = new ArrayList<>();
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            int col = i + 1;
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (Character.isDigit(c)) {
                i = number(s, i, out);
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) j++;
                String w = s.substring(i, j);
                out.add(new Token(keyword(w), w, 0, 0, col));
                i = j;
                continue;
            }
            if (c == '"' || c == '\'') {
                i = string(s, i, out);
                continue;
            }
            char d = i + 1 < n ? s.charAt(i + 1) : 0;
            switch (c) {
                case '.' -> add(out, Kind.DOT, ".", col);
                case ',' -> add(out, Kind.COMMA, ",", col);
                case '(' -> add(out, Kind.LPAREN, "(", col);
                case ')' -> add(out, Kind.RPAREN, ")", col);
                case '[' -> add(out, Kind.LBRACKET, "[", col);
                case ']' -> add(out, Kind.RBRACKET, "]", col);
                case '+' -> add(out, Kind.PLUS, "+", col);
                case '-' -> add(out, Kind.MINUS, "-", col);
                case '*' -> add(out, Kind.STAR, "*", col);
                case '/' -> add(out, Kind.SLASH, "/", col);
                case '=' -> {
                    if (d != '=')
                        throw new ExprException(
                                "'=' at column " + col + ": write '==' to compare", col);
                    add(out, Kind.EQ, "==", col);
                    i++;
                }
                case '!' -> {
                    if (d != '=')
                        throw new ExprException(
                                "'!' at column " + col + ": write 'not' or '!='", col);
                    add(out, Kind.NE, "!=", col);
                    i++;
                }
                case '<' -> {
                    if (d == '=') {
                        add(out, Kind.LE, "<=", col);
                        i++;
                    } else add(out, Kind.LT, "<", col);
                }
                case '>' -> {
                    if (d == '=') {
                        add(out, Kind.GE, ">=", col);
                        i++;
                    } else add(out, Kind.GT, ">", col);
                }
                default -> throw new ExprException("unexpected '" + c + "' at column " + col, col);
            }
            i++;
        }
        out.add(new Token(Kind.EOF, "", 0, 0, n + 1));
        return out;
    }

    private static void add(List<Token> out, Kind k, String text, int col) {
        out.add(new Token(k, text, 0, 0, col));
    }

    private static Kind keyword(String w) {
        return switch (w) {
            case "and" -> Kind.AND;
            case "or" -> Kind.OR;
            case "not" -> Kind.NOT;
            case "in" -> Kind.IN;
            case "true" -> Kind.TRUE;
            case "false" -> Kind.FALSE;
            default -> Kind.IDENT;
        };
    }

    private static int number(String s, int start, List<Token> out) {
        int n = s.length(), i = start;
        while (i < n && Character.isDigit(s.charAt(i))) i++;
        if (i + 1 < n && s.charAt(i) == '.' && Character.isDigit(s.charAt(i + 1))) {
            i++;
            while (i < n && Character.isDigit(s.charAt(i))) i++;
        }
        double value = Double.parseDouble(s.substring(start, i));
        int col = start + 1;
        int u = i;
        while (u < n
                && (Character.isLetter(s.charAt(u))
                        || s.charAt(u) == '_'
                        || Character.isDigit(s.charAt(u)))) u++;
        if (u > i) {
            String unit = s.substring(i, u);
            long ms =
                    switch (unit) {
                        case "ms" -> 1L;
                        case "s" -> 1000L;
                        case "m" -> 60_000L;
                        case "h" -> 3_600_000L;
                        case "d" -> 86_400_000L;
                        default -> -1L;
                    };
            if (ms < 0)
                throw new ExprException(
                        "'"
                                + s.substring(start, u)
                                + "' at column "
                                + col
                                + " is not a number: units are ms, s, m, h, d or %",
                        col);
            out.add(
                    new Token(
                            Kind.DURATION,
                            s.substring(start, u),
                            value,
                            Math.round(value * ms),
                            col));
            return u;
        }
        if (i < n && s.charAt(i) == '%') {
            out.add(new Token(Kind.PERCENT, s.substring(start, i + 1), value / 100.0, 0, col));
            return i + 1;
        }
        out.add(new Token(Kind.NUMBER, s.substring(start, i), value, 0, col));
        return i;
    }

    private static int string(String s, int start, List<Token> out) {
        char quote = s.charAt(start);
        StringBuilder b = new StringBuilder();
        int i = start + 1, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < n) {
                b.append(s.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == quote) {
                out.add(new Token(Kind.STRING, b.toString(), 0, 0, start + 1));
                return i + 1;
            }
            b.append(c);
            i++;
        }
        throw new ExprException("unterminated string starting at column " + (start + 1), start + 1);
    }
}
