package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.ast.AltStmt;
import io.github.llm4j.loom.ast.BroadcastStmt;
import io.github.llm4j.loom.ast.CallStmt;
import io.github.llm4j.loom.ast.DecideStmt;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.HumanPromptStmt;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.WorkflowDef;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Things in a workflow that are almost always a mistake and that a person cannot see by reading the script once: a result that is stored and
 * never read, and above all a question put to a person whose answer changes nothing. It reads the statements generically: any text a
 * statement carries (a prompt, a condition, a note, arguments) may read a variable, so it stays quiet when unsure.
 *
 * <p>A variable whose name starts with an underscore is meant to be ignored and is never reported. The result of a {@code run} task is not
 * reported either: a task is often run for what it does, not for what it returns.
 */
public final class WorkflowLint {

    /** One finding: the line, what it is about, and what is wrong. */
    public record Finding(int line, String construct, String message) {}

    /** Getters that name what a statement assigns, or are not text it carries. */
    private static final Set<String> NOT_TEXT = Set.of("getLine", "getVariableName", "getResultVariable", "getVariable", "getClass");

    private WorkflowLint() {}

    public static List<Finding> check(WorkflowDef workflow) {
        List<Statement> all = new ArrayList<>();
        StatementWalker.walk(workflow.getStatements(), all::add);
        Map<Statement, String> texts = new HashMap<>();
        all.forEach(s -> texts.put(s, text(s)));

        List<Finding> findings = new ArrayList<>();
        String who = "workflow " + workflow.getName();
        Set<String> humanAnswers = new java.util.HashSet<>();
        for (Statement s : all) {
            String name = assigned(s);
            if (name == null) continue;
            if (s instanceof HumanPromptStmt) humanAnswers.add(name);
            Pattern word = Pattern.compile("(?<![\\w.])" + Pattern.quote(name) + "(?![\\w])");
            boolean read = false;
            for (Statement t : all) {
                if (word.matcher(texts.get(t)).find()) {
                    read = true;
                    break;
                }
            }
            if (read) continue;
            findings.add(new Finding(s.getLine(), who, s instanceof HumanPromptStmt
                    ? "the answer to this question is stored in " + name + " and never read, so the workflow does the same whatever the person says"
                    : name + " is set here and never used"));
        }
        for (Statement s : all) {
            if (s instanceof AltStmt alt && !alt.getIfBranch().isEmpty() && signature(alt.getIfBranch()).equals(signature(alt.getElseBranch()))
                    && humanAnswers.stream().anyMatch(a -> Pattern.compile("(?<![\\w.])" + Pattern.quote(a) + "(?![\\w])").matcher(alt.getCondition()).find())) {
                findings.add(new Finding(s.getLine(), who, "both answers to the question lead to the same steps, so the question changes nothing"));
            }
        }
        findings.sort(java.util.Comparator.comparingInt(Finding::line));
        return findings;
    }

    private static String assigned(Statement s) {
        String name = null;
        if (s instanceof DelegateStmt d) name = d.getVariableName();
        else if (s instanceof HumanPromptStmt h) name = h.getVariableName();
        else if (s instanceof BroadcastStmt b) name = b.getVariableName();
        else if (s instanceof CallStmt c) name = c.getResultVariable();
        else if (s instanceof DecideStmt d) name = d.getVariable();
        if (name == null || name.isBlank() || name.startsWith("{") || name.startsWith("_")) return null;
        return name;
    }

    /** Every piece of text a statement carries, except the name it assigns, joined. */
    private static String text(Statement s) {
        StringBuilder out = new StringBuilder();
        for (Method m : s.getClass().getMethods()) {
            if (m.getParameterCount() != 0 || NOT_TEXT.contains(m.getName()) || !(m.getName().startsWith("get") || m.getName().startsWith("is"))) continue;
            try {
                append(out, m.invoke(s));
            } catch (ReflectiveOperationException | RuntimeException e) {
                // a getter that cannot be read carries no text we can rely on
            }
        }
        return out.toString();
    }

    private static void append(StringBuilder out, Object value) {
        if (value instanceof String str) out.append(str).append('\n');
        else if (value instanceof Map<?, ?> map) map.values().forEach(v -> append(out, v));
        else if (value instanceof Collection<?> list) list.forEach(v -> {
            if (!(v instanceof Statement)) append(out, v);
        });
    }

    /** What a list of statements is, for comparing two lists: the kind and text of each, in order. */
    private static String signature(List<Statement> statements) {
        StringBuilder out = new StringBuilder();
        StatementWalker.walk(statements, s -> out.append(s.getClass().getSimpleName()).append('|').append(text(s)).append("||"));
        return out.toString();
    }
}
