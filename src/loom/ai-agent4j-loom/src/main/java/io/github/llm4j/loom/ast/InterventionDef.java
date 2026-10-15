package io.github.llm4j.loom.ast;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code intervention { timeout: retry  user: ask  sibling: discard }}: what the run does by default when an agent is stopped for a reason,
 * for every step that has no {@code on_stopped} of its own.
 */
public class InterventionDef implements Node {

    /** The reasons a stop can have. Anything a person writes that is not one of these counts as {@code user}. */
    public static final List<String> REASONS = List.of("user", "operator", "timeout", "budget", "limit", "sibling", "supervision", "safety", "shutdown");

    /** The fields {@code _stop} has inside an {@code on_stopped} handler. */
    public static final List<String> STOP_FIELDS = List.of("reason", "note", "kind", "phase", "partial", "changed", "origin");

    private final Map<String, OutcomeStmt.Kind> byReason = new LinkedHashMap<>();
    private final Map<String, Integer> lines = new LinkedHashMap<>();
    private int line;

    public int getLine() { return line; }
    public void setLine(int line) { this.line = line; }

    public void put(String reason, OutcomeStmt.Kind outcome, int line) {
        byReason.put(reason, outcome);
        lines.put(reason, line);
    }

    public Map<String, OutcomeStmt.Kind> getByReason() { return byReason; }
    public int lineOf(String reason) { return lines.getOrDefault(reason, line); }
}
