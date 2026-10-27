package io.github.llm4j.loom.review.fix;

import io.github.llm4j.loom.review.model.TextEdit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A script as lines, with the positions fixes are anchored to and the way to apply edits to it.
 * Lines and columns are 1-based. The file's own line ending is kept: text inserted by a fix is
 * written with it, and only the lines an edit touches change.
 */
public final class SourceText {

    private final List<String> lines;
    private final String eol;
    private final boolean endsWithEol;

    private SourceText(List<String> lines, String eol, boolean endsWithEol) {
        this.lines = lines;
        this.eol = eol;
        this.endsWithEol = endsWithEol;
    }

    public static SourceText of(String text) {
        String eol = text.contains("\r\n") ? "\r\n" : "\n";
        boolean ends = text.endsWith("\n");
        List<String> lines = new ArrayList<>(java.util.Arrays.asList(text.split("\\r?\\n", -1)));
        if (ends && lines.size() > 1) lines.remove(lines.size() - 1);
        return new SourceText(lines, eol, ends);
    }

    public int lineCount() {
        return lines.size();
    }

    public String line(int n) {
        return lines.get(n - 1);
    }

    public String eol() {
        return eol;
    }

    public String text() {
        String joined = String.join(eol, lines);
        return endsWithEol ? joined + eol : joined;
    }

    /** The part of a line before a {@code //} comment, ignoring slashes inside a string. */
    public static String code(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"' && (i == 0 || line.charAt(i - 1) != '\\')) quoted = !quoted;
            else if (!quoted && c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/')
                return line.substring(0, i);
        }
        return line;
    }

    /**
     * Where a statement's own words end on its first line, before a trailing comment, handler or
     * brace.
     */
    public int statementEndColumn(int n) {
        String code = code(line(n));
        int cut = code.length();
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("\\s+(on_failure|on_stopped|on_exhausted)\\b")
                        .matcher(code);
        if (m.find()) cut = Math.min(cut, m.start());
        String head = code.substring(0, cut).stripTrailing();
        if (head.endsWith("{")) head = head.substring(0, head.length() - 1).stripTrailing();
        // a line that holds more than this one statement (a braces pair in the middle) is not
        // guessed at
        boolean quoted = false;
        for (int i = 0; i < head.length(); i++) {
            char c = head.charAt(i);
            if (c == '"' && (i == 0 || head.charAt(i - 1) != '\\')) quoted = !quoted;
            else if (!quoted && (c == '{' || c == '}')) return -1;
        }
        return head.length() + 1;
    }

    /** The column of the brace that opens the block a header ends with, or -1. */
    public int beforeBraceColumn(int n) {
        String code = code(line(n)).stripTrailing();
        return code.endsWith("{") ? code.length() : -1;
    }

    public int lineEndColumn(int n) {
        return code(line(n)).stripTrailing().length() + 1;
    }

    /**
     * The last line of the block that starts on line {@code n}, or {@code n} when it opens none.
     */
    public int blockEnd(int n) {
        int depth = 0;
        boolean opened = false;
        for (int i = n; i <= lines.size(); i++) {
            boolean quoted = false;
            String code = code(line(i));
            for (int j = 0; j < code.length(); j++) {
                char c = code.charAt(j);
                if (c == '"' && (j == 0 || code.charAt(j - 1) != '\\')) quoted = !quoted;
                else if (!quoted && c == '{') {
                    depth++;
                    opened = true;
                } else if (!quoted && c == '}') depth--;
            }
            if (opened && depth <= 0) return i;
            if (!opened) return n;
        }
        return n;
    }

    /** True when no edit's range overlaps another's. */
    public static boolean disjoint(List<TextEdit> edits) {
        List<TextEdit> sorted = new ArrayList<>(edits);
        sorted.sort(order());
        for (int i = 1; i < sorted.size(); i++) {
            TextEdit a = sorted.get(i - 1);
            TextEdit b = sorted.get(i);
            if (before(b.startLine(), b.startColumn(), a.endLine(), a.endColumn())) return false;
        }
        return true;
    }

    private static boolean before(int l1, int c1, int l2, int c2) {
        return l1 < l2 || (l1 == l2 && c1 < c2);
    }

    private static Comparator<TextEdit> order() {
        return Comparator.comparingInt(TextEdit::startLine).thenComparingInt(TextEdit::startColumn);
    }

    /**
     * The text with the edits applied, last position first so that earlier positions stay valid.
     */
    public String apply(List<TextEdit> edits) {
        List<TextEdit> sorted = new ArrayList<>(edits);
        sorted.sort(order().reversed());
        String text = text();
        for (TextEdit e : sorted) {
            int from = offset(text, e.startLine(), e.startColumn());
            int to = offset(text, e.endLine(), e.endColumn());
            text = text.substring(0, from) + e.newText().replace("\n", eol) + text.substring(to);
        }
        return text;
    }

    /** The offset of a 1-based line and column in {@code text}; past the last line is the end. */
    private int offset(String text, int line, int column) {
        int off = 0;
        for (int l = 1; l < line; l++) {
            int nl = text.indexOf('\n', off);
            if (nl < 0) return text.length();
            off = nl + 1;
        }
        return Math.min(text.length(), off + column - 1);
    }
}
