package io.github.llm4j.loom.wtest;

import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.execution.TraceListener;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What happened during one case: the trace events the run emitted, the calls the scripted
 * collaborators answered, and the places a case asked for something it had not scripted. It reads
 * the same events {@code --trace} shows, so the interpreter does not know a test is running.
 */
final class Recorder implements TraceListener {

    private final List<TraceEvent> events = new CopyOnWriteArrayList<>();
    private final Map<String, AtomicInteger> calls = new java.util.concurrent.ConcurrentHashMap<>();
    private final List<String> violations = new CopyOnWriteArrayList<>();
    private final List<String> lenient = new CopyOnWriteArrayList<>();

    @Override
    public void onEvent(TraceEvent event) {
        events.add(event);
    }

    List<TraceEvent> events() {
        return events;
    }

    /** Counts a call to a scripted agent or classifier and returns its number (1 for the first). */
    int call(String name) {
        return calls.computeIfAbsent(name, k -> new AtomicInteger()).incrementAndGet();
    }

    int calls(String name) {
        AtomicInteger n = calls.get(name);
        return n == null ? 0 : n.get();
    }

    Map<String, Integer> callCounts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        calls.forEach((k, v) -> out.put(k, v.get()));
        return out;
    }

    void violation(String message) {
        violations.add(message);
    }

    List<String> violations() {
        return violations;
    }

    void lenient(String message) {
        lenient.add(message);
    }

    List<String> lenientNotes() {
        return lenient;
    }

    // ---- reading the events

    /** The steps that ran, as the labels {@code weave graph} gives them. */
    List<String> route() {
        List<String> route = new ArrayList<>();
        for (TraceEvent e : events) {
            String label = label(e);
            if (label != null) route.add(label);
        }
        return route;
    }

    static String label(TraceEvent e) {
        return switch (e.type()) {
            case TraceEvent.DELEGATE_START -> "delegate " + e.agent();
            case TraceEvent.CLASSIFY -> "classify " + e.data().get("classifier");
            case TraceEvent.TASK_START -> "run " + taskName(e);
            case TraceEvent.NOTE -> "note";
            case TraceEvent.CHECKPOINT -> "checkpoint " + e.data().getOrDefault("name", "");
            case TraceEvent.REWIND -> "rewind";
            case TraceEvent.HALT -> "halt";
            case TraceEvent.SLEEP -> "start".equals(String.valueOf(e.data().get("state")))
                    ? "sleep"
                    : null;
            default -> null;
        };
    }

    private static String taskName(TraceEvent e) {
        Object n = e.data().get("name");
        if (n != null) return String.valueOf(n);
        String t = e.text() == null ? "" : e.text();
        return t.startsWith("run ") ? t.substring(4) : t;
    }

    /** How many times each tool was called. */
    Map<String, Integer> toolCalls() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (TraceEvent e : events)
            if (TraceEvent.TOOL_CALL.equals(e.type()) && e.data().get("tool") != null)
                out.merge(String.valueOf(e.data().get("tool")), 1, Integer::sum);
        return out;
    }

    /** How many times each task ran. */
    Map<String, Integer> taskCalls() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (TraceEvent e : events)
            if (TraceEvent.TASK_START.equals(e.type())) out.merge(taskName(e), 1, Integer::sum);
        return out;
    }

    /** How the run ended, from the last {@code run_end} event; empty when none was emitted. */
    String status() {
        String status = "";
        for (TraceEvent e : events)
            if (TraceEvent.RUN_END.equals(e.type()))
                status = String.valueOf(e.data().get("status"));
        return status;
    }
}
