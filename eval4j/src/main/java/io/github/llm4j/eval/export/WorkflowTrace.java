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
     * A workflow statement. {@code kind}: start, end, delegate, alt, loop, handoff, human_prompt,
     * parallel, checkpoint.
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
