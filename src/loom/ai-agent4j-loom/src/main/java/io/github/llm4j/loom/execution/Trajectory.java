package io.github.llm4j.loom.execution;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a child run has done, as facts: how often each tool, agent and task was called, in what order
 * each was first called, which checkpoints it reached and which calls were refused (phase 33f, D24,
 * and the facts a judge sees by default in D25). Read from the run's own trace, so nothing is asked
 * of the child and no tool argument or result is kept.
 */
public final class Trajectory implements TraceListener {

    /** A moment's copy: safe to read while the child goes on. */
    public record Snapshot(
            Map<String, Integer> calls,
            List<String> firstCallOrder,
            Set<String> reached,
            Map<String, Integer> refused) {

        /** The record of a child that has done nothing. */
        public static final Snapshot EMPTY = new Snapshot(Map.of(), List.of(), Set.of(), Map.of());

        public Snapshot {
            calls = Map.copyOf(calls);
            firstCallOrder = List.copyOf(firstCallOrder);
            reached = Set.copyOf(reached);
            refused = Map.copyOf(refused);
        }

        /** How many times {@code name} was called. */
        public int callsOf(String name) {
            return calls.getOrDefault(name, 0);
        }

        /** How many calls of {@code name} were refused. */
        public int refusedOf(String name) {
            return refused.getOrDefault(name, 0);
        }
    }

    private final Map<String, Integer> calls = new LinkedHashMap<>();
    private final List<String> order = new ArrayList<>();
    private final Set<String> reached = new LinkedHashSet<>();
    private final Map<String, Integer> refused = new LinkedHashMap<>();

    @Override
    public synchronized void onEvent(TraceEvent event) {
        Map<String, Object> data = event.data() == null ? Map.of() : event.data();
        switch (event.type()) {
            case TraceEvent.TOOL_CALL -> called(data.get("tool"));
            case TraceEvent.TASK_START -> called(data.get("task"));
            case TraceEvent.DELEGATE_START -> called(event.agent());
            case TraceEvent.CHECKPOINT -> {
                if (data.get("checkpoint") != null) reached.add(String.valueOf(data.get("checkpoint")));
            }
            default -> {}
        }
    }

    private void called(Object name) {
        if (name == null) return;
        String key = String.valueOf(name);
        if (calls.merge(key, 1, Integer::sum) == 1) order.add(key);
    }

    /** A call was refused before it ran. */
    synchronized void refused(String name) {
        refused.merge(name, 1, Integer::sum);
    }

    /**
     * Takes what an earlier run of the same child had done, for a re-run over its journal: a replay
     * does not emit the events of steps it skips, so without this the facts of a child would start
     * again from nothing (phase 34, D12 rule 7). Only adds; never lowers a count.
     */
    synchronized void carry(Snapshot earlier) {
        earlier.calls().forEach((name, n) -> calls.merge(name, n, Math::max));
        for (String name : earlier.firstCallOrder()) if (!order.contains(name)) order.add(name);
        reached.addAll(earlier.reached());
        earlier.refused().forEach((name, n) -> refused.merge(name, n, Math::max));
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(calls, order, reached, refused);
    }
}
