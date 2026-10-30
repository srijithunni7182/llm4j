package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.WorkflowDef;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Moving a script from {@code substitution: legacy} to {@code braces}: what would change (a
 * variable's name written as a plain word in text the runtime substitutes into), and the rewrite of
 * the plain cases. A word standing alone is taken to be meant as the variable; a word inside
 * another ({@code length-out-of-range}) is left for the person to decide.
 */
public final class SubstitutionMigration {

    /** One string that would change: where, which variable, and whether the word stands alone. */
    public record Change(int line, String variable, String position, boolean standalone) {}

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[^{}\\s]+}");

    private SubstitutionMigration() {}

    /** Every place a bare variable name would stop being replaced under {@code braces}. */
    public static List<Change> preview(LoomScript script, String source) {
        List<Change> out = new ArrayList<>();
        for (WorkflowDef w : script.getWorkflows()) {
            List<Statement> all = new ArrayList<>();
            StatementWalker.walk(w.getStatements(), all::add);
            Set<String> names = new LinkedHashSet<>(w.getParameters());
            for (Statement s : all) {
                String n = WorkflowLint.assignedName(s);
                if (n != null) names.add(n);
            }
            for (Statement s : all)
                for (SubstitutedText.Entry e : SubstitutedText.entries(s)) {
                    String bare = PLACEHOLDER.matcher(e.text()).replaceAll("");
                    for (String name : names) {
                        if (name == null
                                || name.length() < 2
                                || name.startsWith("_")
                                || name.startsWith("{")) continue;
                        if (!bare.contains(name)) continue;
                        boolean alone = Pattern.compile(standalone(name)).matcher(bare).find();
                        out.add(new Change(s.getLine(), name, e.position(), alone));
                    }
                }
        }
        return out;
    }

    private static String standalone(String name) {
        return "(?<![\\w.-])" + Pattern.quote(name) + "(?![\\w.-])";
    }

    /**
     * The source with the standalone words of the given changes written as {@code {name}}. A line
     * is only changed inside a quoted string, and only where the text is on the one line.
     */
    public static String rewrite(String source, List<Change> changes) {
        String[] lines = source.split("\n", -1);
        for (Change c : changes) {
            if (!c.standalone() || c.line() < 1 || c.line() > lines.length) continue;
            lines[c.line() - 1] = wrapInStrings(lines[c.line() - 1], c.variable());
        }
        return String.join("\n", lines);
    }

    private static String wrapInStrings(String line, String name) {
        Matcher m = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"").matcher(line);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(line, last, m.start());
            String literal = m.group();
            // leave the braced placeholders alone, wrap only the words outside them
            Matcher p = PLACEHOLDER.matcher(literal);
            StringBuilder lit = new StringBuilder();
            int at = 0;
            while (p.find()) {
                lit.append(wrapWords(literal.substring(at, p.start()), name)).append(p.group());
                at = p.end();
            }
            lit.append(wrapWords(literal.substring(at), name));
            out.append(lit);
            last = m.end();
        }
        out.append(line.substring(last));
        return out.toString();
    }

    private static String wrapWords(String text, String name) {
        return text.replaceAll(standalone(name), Matcher.quoteReplacement("{" + name + "}"));
    }

    /** The changed lines, each as its number and the line before and after. */
    public static String diff(String before, String after) {
        String[] a = before.split("\n", -1);
        String[] b = after.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(a.length, b.length); i++)
            if (!a[i].equals(b[i]))
                out.append("line ")
                        .append(i + 1)
                        .append('\n')
                        .append("- ")
                        .append(a[i].strip())
                        .append('\n')
                        .append("+ ")
                        .append(b[i].strip())
                        .append('\n');
        return out.toString();
    }
}
