package io.github.llm4j.loom.parser;

import io.github.llm4j.loom.ast.LadderDef;
import io.github.llm4j.loom.ast.MeasureDef;
import io.github.llm4j.loom.ast.RungDef;
import io.github.llm4j.loom.autonomy.expr.Expr;
import io.github.llm4j.loom.autonomy.expr.ExprException;
import io.github.llm4j.loom.autonomy.expr.ExprParser;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads the text of a {@code ladder} or {@code measure} declaration. These are read from raw text and not from Loom's tokens because their
 * conditions are expressions ({@code *}, {@code /}, {@code 14d}, method chains) in a language of their own; the Loom lexer sets the declaration
 * aside whole, using {@link #ladderEnd} and {@link #measureEnd} to find where it stops.
 */
public final class LadderParser {

    private static final Pattern ENDS_OPEN = Pattern.compile("(?:^|[^A-Za-z0-9_])(?:and|or|not|in)$|[,+\\-*/<>=!(\\[.]$");
    private static final Pattern STARTS_ON = Pattern.compile("^(?:and\\b|or\\b|[.,+*/<>=!-])");

    private final String text;
    private final int startLine;
    private int pos;

    private LadderParser(String raw, int startLine) {
        this.text = stripComments(raw);
        this.startLine = startLine;
    }

    // ---------- extents (used by the lexer) ----------

    /** The raw text with every {@code //} comment replaced by spaces, so offsets and line numbers stay the same. */
    public static String stripComments(String s) {
        StringBuilder b = new StringBuilder(s.length());
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                b.append(c);
                if (c == '\\' && i + 1 < s.length()) b.append(s.charAt(++i));
                else if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
                b.append(c);
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                while (i < s.length() && s.charAt(i) != '\n') {
                    b.append(' ');
                    i++;
                }
                if (i < s.length()) b.append('\n');
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    /** The index just after the closing brace of the ladder that starts at {@code from}, or -1 when it never closes. */
    public static int ladderEnd(String source, int from) {
        String s = stripComments(source);
        int open = s.indexOf('{', from);
        if (open < 0) return -1;
        int depth = 0;
        char quote = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i + 1;
            }
        }
        return -1;
    }

    /** The index just after the measure declaration that starts at {@code from}: its expression runs on while it is unfinished. */
    public static int measureEnd(String source, int from) {
        String s = stripComments(source);
        int eq = s.indexOf('=', from);
        int nl = s.indexOf('\n', from);
        if (eq < 0 || (nl >= 0 && nl < eq && s.indexOf(')', from) >= 0 && s.indexOf(')', from) > nl)) return nl < 0 ? s.length() : nl;
        return new LadderParser(s.substring(eq + 1), 0).expressionEnd(false) + eq + 1;
    }

    // ---------- ladder ----------

    public static LadderDef parseLadder(String raw, int startLine) {
        return new LadderParser(raw, startLine).ladder();
    }

    public static MeasureDef parseMeasure(String raw, int startLine) {
        return new LadderParser(raw, startLine).measure();
    }

    private RuntimeException error(int at, String message) {
        int line = startLine;
        for (int i = 0; i < Math.min(at, text.length()); i++) if (text.charAt(i) == '\n') line++;
        return new RuntimeException("Parse error at line " + line + ": " + message);
    }

    private int lineAt(int at) {
        int line = startLine;
        for (int i = 0; i < Math.min(at, text.length()); i++) if (text.charAt(i) == '\n') line++;
        return line;
    }

    private void ws() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
    }

    /** The position of the next thing, after any white space. */
    private int here() {
        ws();
        return pos;
    }

    private boolean at(char c) {
        ws();
        return pos < text.length() && text.charAt(pos) == c;
    }

    private boolean eat(char c) {
        if (!at(c)) return false;
        pos++;
        return true;
    }

    private void expect(char c, String message) {
        ws();
        if (!eat(c)) throw error(pos, message);
    }

    /** A word: letters, digits, underscores, and hyphens between them (support-lead). Empty when there is none here. */
    private String word() {
        ws();
        int s = pos;
        while (pos < text.length()) {
            char c = text.charAt(pos);
            boolean hyphen = c == '-' && pos + 1 < text.length() && Character.isLetterOrDigit(text.charAt(pos + 1)) && pos > s;
            if (Character.isLetterOrDigit(c) || c == '_' || hyphen) pos++;
            else break;
        }
        return text.substring(s, pos);
    }

    private String peekWord() {
        int save = pos;
        String w = word();
        pos = save;
        return w;
    }

    private LadderDef ladder() {
        int start = here();
        if (!word().equals("ladder")) throw error(start, "Expect 'ladder'.");
        int nameAt = here();
        String name = word();
        if (name.isEmpty()) throw error(nameAt, "Expect a ladder name, as in: ladder RefundLadder { rung watch { mode: blind } }");
        LadderDef def = new LadderDef(name, lineAt(start));
        if (eat('(')) {
            do {
                int p = here();
                String param = word();
                if (param.isEmpty()) throw error(p, "Expect a parameter name in ladder " + name + ".");
                def.getParams().add(param);
                ws();
            } while (eat(','));
            expect(')', "Expect ',' or ')' in the parameters of ladder " + name + ".");
        }
        expect('{', "Expect '{' after ladder " + name + ".");
        while (true) {
            ws();
            if (pos >= text.length()) throw error(pos, "Expect '}' to close ladder " + name + ".");
            if (eat('}')) break;
            int at = pos;
            String w = word();
            if (w.equals("rung")) {
                RungDef r = rung(def);
                for (RungDef other : def.getRungs()) {
                    if (other.getName().equals(r.getName())) throw error(at, "rung " + r.getName() + " is declared twice in ladder " + name + ".");
                }
                def.getRungs().add(r);
            } else if (w.equals("climbing")) {
                climbing(def, at);
            } else {
                throw error(at, "Unexpected \"" + (w.isEmpty() ? String.valueOf(text.charAt(pos)) : w) + "\" in ladder " + name
                        + ". A ladder holds rung blocks, and: climbing needs approval from NAME, or: climbing is automatic.");
            }
        }
        return def;
    }

    private void climbing(LadderDef def, int at) {
        String usage = "Write: climbing needs approval from NAME, or: climbing is automatic";
        String w = word();
        if (w.equals("is")) {
            if (!word().equals("automatic")) throw error(at, usage);
            def.setAutomatic();
        } else if (w.equals("needs")) {
            if (!word().equals("approval") || !word().equals("from")) throw error(at, usage);
            String who = word();
            if (who.isEmpty()) throw error(at, usage);
            def.setApprover(who);
        } else {
            throw error(at, usage);
        }
    }

    private RungDef rung(LadderDef ladder) {
        int nameAt = here();
        String name = word();
        if (name.isEmpty()) throw error(nameAt, "Expect a rung name, as in: rung watch { mode: blind }");
        RungDef r = new RungDef(name, lineAt(nameAt));
        expect('{', "Expect '{' after rung " + name + ".");
        while (true) {
            ws();
            if (pos >= text.length()) throw error(pos, "Expect '}' to close rung " + name + ".");
            if (eat('}')) break;
            int at = pos;
            String w = word();
            switch (w) {
                case "mode": {
                    if (r.getMode() != null) throw error(at, "mode is given twice in rung " + name + ".");
                    expect(':', "Write: mode: blind, confirm or autonomous");
                    int m = here();
                    String mode = word();
                    RungDef.Mode parsed = RungDef.Mode.of(mode);
                    if (parsed == null) throw error(m, "A rung's mode must be blind, confirm or autonomous (not \"" + mode + "\").");
                    r.setMode(parsed, lineAt(m));
                    break;
                }
                case "climb": {
                    if (!word().equals("when")) throw error(at, "Write: climb when <condition>");
                    int line = lineAt(pos);
                    String cond = condition("climb when", " and ", at);
                    r.setClimb(parse(cond, line), cond, line);
                    break;
                }
                case "slip": {
                    String to = null;
                    if (peekWord().equals("to")) {
                        word();
                        to = word();
                        if (to.isEmpty()) throw error(at, "Write: slip to <rung> when <condition>");
                    }
                    if (!word().equals("when")) throw error(at, "Write: slip when <condition>, or: slip to <rung> when <condition>");
                    int line = lineAt(pos);
                    String cond = condition("slip when", " or ", at);
                    r.setSlip(parse(cond, line), cond, to, line);
                    break;
                }
                case "sample": {
                    String usage = "Write: sample 5% blind";
                    ws();
                    int s = pos;
                    while (pos < text.length() && (Character.isDigit(text.charAt(pos)) || text.charAt(pos) == '.')) pos++;
                    if (s == pos || pos >= text.length() || text.charAt(pos) != '%') throw error(at, usage);
                    double share = Double.parseDouble(text.substring(s, pos)) / 100.0;
                    pos++;
                    if (!word().equals("blind")) throw error(at, usage);
                    r.setSample(share, lineAt(at));
                    break;
                }
                case "escalate": {
                    if (!word().equals("when")) throw error(at, "Write: escalate when <condition>");
                    int line = lineAt(pos);
                    String cond = condition("escalate when", " or ", at);
                    r.setEscalate(parse(cond, line), cond, line);
                    break;
                }
                default:
                    throw error(at, "Unexpected \"" + (w.isEmpty() ? String.valueOf(text.charAt(pos)) : w) + "\" in rung " + name
                            + ". A rung holds: mode, climb when, slip when, sample, escalate when.");
            }
        }
        if (r.getMode() == null) throw error(nameAt, "A rung needs a mode: mode: blind, confirm or autonomous (rung " + name + ").");
        return r;
    }

    /** The condition after {@code climb when}: a comma list is "a, b, c" for "a and b and c" (climb) or "a or b or c" (slip, escalate). */
    private String condition(String after, String joiner, int at) {
        ws();
        int s = pos;
        pos = expressionEnd(true);
        String raw = text.substring(s, pos).replace('\n', ' ').trim();
        if (raw.isEmpty()) throw error(at, "Write a condition after \"" + after + "\".");
        List<String> parts = topLevelCommas(raw);
        if (parts.size() == 1) return raw;
        List<String> wrapped = new ArrayList<>();
        for (String p : parts) {
            if (p.isBlank()) throw error(at, "A comma in the condition after \"" + after + "\" needs a condition on each side.");
            wrapped.add("(" + p.trim() + ")");
        }
        return String.join(joiner, wrapped);
    }

    private Expr parse(String cond, int line) {
        try {
            return ExprParser.parse(cond);
        } catch (ExprException e) {
            throw new RuntimeException("Parse error at line " + line + ": " + e.getMessage() + " (in: " + cond + ")");
        }
    }

    private static List<String> topLevelCommas(String s) {
        List<String> parts = new ArrayList<>();
        int depth = 0, last = 0;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '(' || c == '[') {
                depth++;
            } else if (c == ')' || c == ']') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(s.substring(last, i));
                last = i + 1;
            }
        }
        parts.add(s.substring(last));
        return parts;
    }

    /**
     * Where an expression that starts at the current position ends: at a line break when brackets are closed and the expression is complete
     * (it does not end in an operator and the next line does not carry on with {@code and}, {@code or}, a dot or an operator), or at a closing brace.
     */
    private int expressionEnd(boolean stopAtBrace) {
        int depth = 0;
        char quote = 0;
        StringBuilder sofar = new StringBuilder();
        int i = pos;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (quote != 0) {
                sofar.append(c);
                if (c == '\\' && i + 1 < text.length()) sofar.append(text.charAt(++i));
                else if (c == quote) quote = 0;
                i++;
                continue;
            }
            if (c == '"' || c == '\'') quote = c;
            if (c == '(' || c == '[') depth++;
            if (c == ')' || c == ']') depth--;
            if (c == '}' && stopAtBrace) return i;
            if (stopAtBrace && depth <= 0 && Character.isLetter(c) && i > 0 && Character.isWhitespace(text.charAt(i - 1)) && !sofar.toString().isBlank()
                    && !ENDS_OPEN.matcher(sofar.toString().trim()).find() && nextWordIsClause(i)) {
                return i;                                            // the next clause of the rung begins on the same line
            }
            if (c == '\n' && depth <= 0) {
                String done = sofar.toString().trim();
                int j = i;
                while (j < text.length() && Character.isWhitespace(text.charAt(j))) j++;
                String rest = j < text.length() ? text.substring(j, Math.min(text.length(), j + 4)) : "";
                boolean carries = done.isEmpty() || ENDS_OPEN.matcher(done).find() || STARTS_ON.matcher(rest).find();
                if (!carries || j >= text.length()) return i;
            }
            sofar.append(c);
            i++;
        }
        return text.length();
    }

    private boolean nextWordIsClause(int at) {
        int j = at;
        while (j < text.length() && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j) == '_')) j++;
        String word = text.substring(at, j);
        boolean clause = word.equals("slip") || word.equals("sample") || word.equals("escalate") || word.equals("climb") || word.equals("mode") || word.equals("rung");
        return clause && (j >= text.length() || Character.isWhitespace(text.charAt(j)));
    }

    // ---------- measure ----------

    private MeasureDef measure() {
        int start = here();
        if (!word().equals("measure")) throw error(start, "Expect 'measure'.");
        String usage = "Write: measure name(c) = expression";
        int nameAt = here();
        String name = word();
        if (name.isEmpty() || !at('(')) throw error(nameAt, usage);
        pos++;
        List<String> params = new ArrayList<>();
        ws();
        if (!at(')')) {
            do {
                String p = word();
                if (p.isEmpty()) throw error(pos, usage);
                params.add(p);
            } while (eat(','));
        }
        expect(')', usage);
        if (params.size() != 1) throw error(nameAt, "A measure takes exactly one case: measure " + name + "(c) = expression");
        expect('=', usage);
        ws();
        int line = lineAt(pos);
        String body = text.substring(pos).replace('\n', ' ').trim();
        if (body.isEmpty()) throw error(pos, "Write an expression after \"=\".");
        return new MeasureDef(name, params.get(0), parse(body, line), body, lineAt(start));
    }
}
