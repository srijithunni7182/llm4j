package io.github.llm4j.loom.debug;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** The places a stepper stops on its own. */
public final class Breakpoints {

    /**
     * One place to stop. {@code kind}: {@code line} (before the statement written there, or the
     * next one), {@code node} (a graph node, {@code n3}), {@code agent} (any statement that asks
     * the agent or classifier) or {@code when} (before any statement, when the condition holds).
     */
    public record Breakpoint(int id, String kind, String target, Integer line) {
        public String describe() {
            return switch (kind) {
                case "line" -> "line "
                        + target
                        + (line != null && !target.equals("" + line)
                                ? " (at line " + line + ")"
                                : "");
                case "node" -> "node " + target;
                case "agent" -> "statements for " + target;
                default -> "when " + target;
            };
        }
    }

    private static final String PATH = "[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+|\\[[0-9]+\\])*";
    private static final Pattern COMPARISON =
            Pattern.compile(
                    "\\s*"
                            + PATH
                            + "\\s*(?:==|!=|>=|<=|>|<)\\s*(?:\"[^\"]*\"|-?[0-9]+(?:\\.[0-9]+)?|[A-Za-z0-9_.]+)\\s*");
    private static final Pattern IS_OK =
            Pattern.compile("\\s*" + PATH + "\\s+is\\s+(?:not\\s+)?ok\\s*");
    private static final Pattern FLAG = Pattern.compile("\\s*" + PATH + "\\s*");

    /**
     * Why a condition cannot be a break condition, in words for the person typing it, or null when
     * it can: the same forms a script's {@code alt} accepts.
     */
    public static String problemWith(String condition) {
        if (condition == null || condition.isBlank())
            return "A break condition is empty. Write one like intent.urgent == \"yes\", count > 3 or reply is ok.";
        if (COMPARISON.matcher(condition).matches()
                || IS_OK.matcher(condition).matches()
                || FLAG.matcher(condition).matches()) return null;
        return "\""
                + condition.strip()
                + "\" is not a condition the language knows. Write one like intent.urgent == \"yes\", count > 3, reply is ok or a flag name.";
    }

    private final List<Breakpoint> list = new ArrayList<>();
    private int next = 1;

    public synchronized Breakpoint add(String kind, String target, Integer line) {
        Breakpoint b = new Breakpoint(next++, kind, target, line);
        list.add(b);
        return b;
    }

    public synchronized boolean remove(int id) {
        return list.removeIf(b -> b.id() == id);
    }

    public synchronized void clear() {
        list.clear();
    }

    public synchronized List<Breakpoint> all() {
        return List.copyOf(list);
    }
}
