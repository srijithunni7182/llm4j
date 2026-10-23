package io.github.llm4j.loom.symbols;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.lexer.Token;
import io.github.llm4j.loom.lexer.TokenType;
import io.github.llm4j.loom.parser.LoomParser;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a script that may be half typed, with the language's own lexer and parser. Where either
 * refuses, the offending character (lexer) or top-level block (parser) is blanked out, keeping
 * every other position, and the rest is read again: what can be read is returned, and the problems
 * are listed. The tokens are those of the whole text, so a block that could not be parsed still
 * gives its names and places.
 *
 * @param tokens every token that could be lexed (positions are the text's own)
 * @param script the parts that parsed, or an empty script
 */
record TolerantParse(List<Token> tokens, LoomScript script, List<SymbolIndex.Problem> problems) {

    private static final Pattern LINE = Pattern.compile("line (\\d+)");
    private static final Pattern CHARACTER = Pattern.compile("Unexpected character '(.)'");
    private static final int ATTEMPTS = 40;

    static TolerantParse of(String file, String text) {
        List<SymbolIndex.Problem> problems = new ArrayList<>();
        String lexable = text;
        List<Token> tokens = null;
        for (int attempt = 0; attempt < ATTEMPTS && tokens == null; attempt++) {
            try {
                tokens = new Lexer(lexable).tokenize();
            } catch (RuntimeException e) {
                String message = message(e);
                int line = lineOf(message);
                problems.add(new SymbolIndex.Problem(file, line, message));
                String next = blankLexical(lexable, message, line);
                if (next.equals(lexable)) break;
                lexable = next;
            }
        }
        if (tokens == null) return new TolerantParse(List.of(), new LoomScript(), problems);

        String parsable = lexable;
        List<Token> parsed = tokens;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            try {
                LoomScript script = new LoomParser(new Lexer(parsable).tokenize()).parseScript();
                return new TolerantParse(tokens, script, problems);
            } catch (RuntimeException e) {
                String message = message(e);
                int line = lineOf(message);
                problems.add(new SymbolIndex.Problem(file, line, message));
                String next = blankBlock(parsable, parsed, line);
                if (next.equals(parsable)) break;
                parsable = next;
                parsed = new Lexer(parsable).tokenize();
            }
        }
        return new TolerantParse(tokens, new LoomScript(), problems);
    }

    private static String message(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static int lineOf(String message) {
        Matcher m = LINE.matcher(message);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    // ---- lexer trouble

    /** Blanks what the lexer refused: the stray character, the stray quote, or the whole line. */
    private static String blankLexical(String text, String message, int line) {
        boolean[] literal = literalMask(text);
        if (message.startsWith("Unterminated string")) {
            int quote = lastOpenQuote(text, literal);
            return quote < 0 ? text : blank(text, quote, quote + 1);
        }
        Matcher m = CHARACTER.matcher(message);
        if (m.find() && line > 0) {
            char c = m.group(1).charAt(0);
            int from = lineStart(text, line);
            int to = lineEnd(text, line);
            for (int i = from; i < to; i++)
                if (text.charAt(i) == c && !literal[i]) return blank(text, i, i + 1);
        }
        if (line > 0) return blank(text, lineStart(text, line), lineEnd(text, line));
        return text;
    }

    /** True at each index that is inside a string or a comment. */
    private static boolean[] literalMask(String text) {
        boolean[] mask = new boolean[text.length()];
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                mask[i] = true;
                if (c == '"') inString = false;
            } else if (c == '"') {
                inString = true;
                mask[i] = true;
            } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                while (i < text.length() && text.charAt(i) != '\n') mask[i++] = true;
            }
        }
        return mask;
    }

    /** The opening quote of the string that never closes (an odd quote, from the end). */
    private static int lastOpenQuote(String text, boolean[] literal) {
        int open = -1;
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '"') inString = false;
            } else if (c == '"') {
                inString = true;
                open = i;
            } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                while (i < text.length() && text.charAt(i) != '\n') i++;
            }
        }
        return inString ? open : -1;
    }

    // ---- parser trouble

    /**
     * Blanks the top-level block that holds the error line (or, when none does, the line itself). A
     * block runs from its first token to the brace that closes it; one that never closes runs to
     * the end.
     */
    private static String blankBlock(String text, List<Token> tokens, int line) {
        if (line <= 0) return text;
        int i = 0;
        while (i < tokens.size() && tokens.get(i).getType() != TokenType.EOF) {
            Token first = tokens.get(i);
            int end = i;
            if (first.getType() == TokenType.IMPORT) {
                end = Math.min(i + 1, tokens.size() - 1);
            } else if (first.getType() == TokenType.LADDER_DECL
                    || first.getType() == TokenType.MEASURE_DECL) {
                end = i;
            } else {
                int depth = 0;
                boolean opened = false;
                end = tokens.size() - 1;
                for (int j = i; j < tokens.size(); j++) {
                    TokenType type = tokens.get(j).getType();
                    if (type == TokenType.EOF) {
                        end = j - 1;
                        break;
                    }
                    if (type == TokenType.LBRACE) {
                        depth++;
                        opened = true;
                    }
                    if (type == TokenType.RBRACE) depth--;
                    if (opened && depth <= 0) {
                        end = j;
                        break;
                    }
                }
            }
            Token last = tokens.get(Math.max(i, end));
            if (first.getLine() <= line && line <= endLine(last)) {
                int to =
                        last.getType() == TokenType.EOF
                                ? text.length()
                                : offset(text, last) + width(last);
                if (end == tokens.size() - 1 || end < i) to = text.length();
                return blank(text, offset(text, first), to);
            }
            i = Math.max(i, end) + 1;
        }
        return blank(text, lineStart(text, line), lineEnd(text, line));
    }

    private static int endLine(Token t) {
        if (t.getType() == TokenType.LADDER_DECL || t.getType() == TokenType.MEASURE_DECL)
            return t.getLine() + (int) t.getValue().chars().filter(c -> c == '\n').count();
        return t.getLine();
    }

    private static int width(Token t) {
        return switch (t.getType()) {
            case STRING_LITERAL -> t.getValue().length() + 2;
            case EOF -> 0;
            case LBRACE,
                    RBRACE,
                    LPAREN,
                    RPAREN,
                    LBRACKET,
                    RBRACKET,
                    COMMA,
                    COLON,
                    ASSIGN,
                    LT,
                    GT,
                    PLUS,
                    PERCENT,
                    PIPE -> 1;
            case EQUALS, NOT_EQUALS, LTE, GTE, ARROW -> 2;
            default -> t.getValue().length();
        };
    }

    // ---- text positions

    static int offset(String text, Token t) {
        return lineStart(text, t.getLine()) + t.getColumn() - 1;
    }

    static int lineStart(String text, int line) {
        int at = 0;
        for (int l = 1; l < line; l++) {
            int nl = text.indexOf('\n', at);
            if (nl < 0) return text.length();
            at = nl + 1;
        }
        return at;
    }

    private static int lineEnd(String text, int line) {
        int nl = text.indexOf('\n', lineStart(text, line));
        return nl < 0 ? text.length() : nl;
    }

    /** The text with [from, to) turned into spaces, newlines kept. */
    private static String blank(String text, int from, int to) {
        StringBuilder sb = new StringBuilder(text);
        for (int i = Math.max(0, from); i < Math.min(to, sb.length()); i++) {
            char c = sb.charAt(i);
            if (c != '\n' && c != '\r') sb.setCharAt(i, ' ');
        }
        return sb.toString();
    }
}
