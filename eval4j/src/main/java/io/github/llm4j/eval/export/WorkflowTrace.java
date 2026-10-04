package io.github.llm4j.eval.export;

import java.util.List;
import java.util.Map;

/**
 * A workflow run in a neutral shape (it mirrors {@code trace.schema.json}). eval4j knows nothing of
 * any particular workflow engine: a bridge, for example the Loom bridge in {@code eval4j-report},
 * builds one of these, and the trajectory assertions and the report read it.
 *
 * @param nodes the workflow's structure: one node per statement plus {@code start} and {@code end}
 * @param expectedPath node ids in the order the workflow should visit them; may be empty
 * @param actualPath node ids in the order they were visited
 */
public record WorkflowTrace(
        String name,
        List<Node> nodes,
        List<Edge> edges,
        List<String> expectedPath,
        List<String> actualPath,
        List<Event> events,
        List<SpendLine> spend,
        Double budgetUsd,
        int rewinds,
        Integer rewindCap) {

    /**
     * A workflow statement. {@code kind}: start, end, delegate, task, alt, loop, handoff,
     * human_prompt, parallel, checkpoint.
     */
    public record Node(String id, String kind, String label, String agent, Integer bound) {}

    public record Edge(String from, String to, String label) {}

    /** One trace event; {@code t} is seconds since the run's first event. */
    public record Event(
            double t,
            String type,
            String agent,
            String step,
            String node,
            String text,
            Map<String, Object> data) {}

    public record SpendLine(
            String step,
            String agent,
            String model,
            long promptTokens,
            long completionTokens,
            long calls,
            Double costUsd,
            boolean estimated) {}

    public WorkflowTrace {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        expectedPath = expectedPath == null ? List.of() : List.copyOf(expectedPath);
        actualPath = actualPath == null ? List.of() : List.copyOf(actualPath);
        events = events == null ? List.of() : List.copyOf(events);
        spend = spend == null ? List.of() : List.copyOf(spend);
    }

    public WorkflowTrace withExpectedPath(List<String> path) {
        return new WorkflowTrace(
                name, nodes, edges, path, actualPath, events, spend, budgetUsd, rewinds, rewindCap);
    }

    /** Agents in the order their first delegation started. */
    public List<String> agentsInOrder() {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (Event e : events) {
            if ("delegate_start".equals(e.type()) && e.agent() != null) {
                out.add(e.agent());
            }
        }
        return List.copyOf(out);
    }

    /**
     * How many times {@code agent} was delegated to (every delegation counts, unlike {@link
     * #agentsInOrder()}).
     */
    public long delegationsTo(String agent) {
        return events.stream()
                .filter(e -> "delegate_start".equals(e.type()) && agent.equals(e.agent()))
                .count();
    }

    /** Delegations per agent, in the order each agent was first used. */
    public java.util.Map<String, Long> delegationCounts() {
        java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
        for (Event e : events) {
            if ("delegate_start".equals(e.type()) && e.agent() != null) {
                out.merge(e.agent(), 1L, Long::sum);
            }
        }
        return out;
    }

    /**
     * Tasks (deterministic steps: plain code, no model) in the order they first ran. A task that
     * was only replayed from a journal did not run in this trace and is not counted.
     */
    public List<String> tasksInOrder() {
        return List.copyOf(taskCounts().keySet());
    }

    /** How many times {@code task} ran (every run counts; replays from a journal do not). */
    public long taskRuns(String task) {
        return events.stream().filter(e -> isTaskStart(e) && task.equals(e.data().get("task"))).count();
    }

    /** The task names in the order each ran, repeats included. */
    public List<String> taskSequence() {
        List<String> out = new java.util.ArrayList<>();
        for (Event e : events) {
            if (isTaskStart(e)) {
                out.add(String.valueOf(e.data().get("task")));
            }
        }
        return List.copyOf(out);
    }

    /** Runs per task, in the order each task first ran. */
    public java.util.Map<String, Long> taskCounts() {
        java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
        for (String t : taskSequence()) {
            out.merge(t, 1L, Long::sum);
        }
        return out;
    }

    /** The outcomes {@code task} ended with, in order ({@code task_end} events). */
    public List<String> taskOutcomes(String task) {
        List<String> out = new java.util.ArrayList<>();
        for (Event e : events) {
            if ("task_end".equals(e.type())
                    && e.data() != null
                    && task.equals(e.data().get("task"))
                    && e.data().get("outcome") != null) {
                out.add(String.valueOf(e.data().get("outcome")));
            }
        }
        return List.copyOf(out);
    }

    private static boolean isTaskStart(Event e) {
        return "task_start".equals(e.type()) && e.data() != null && e.data().get("task") != null;
    }

    public double totalCostUsd() {
        double sum = 0;
        for (SpendLine s : spend) {
            if (s.costUsd() != null) {
                sum += s.costUsd();
            }
        }
        return sum;
    }
}
