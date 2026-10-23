package io.github.llm4j.loom.overlay;

import io.github.llm4j.loom.graph.GraphEdge;
import io.github.llm4j.loom.graph.GraphNode;
import io.github.llm4j.loom.graph.GraphResult;
import io.github.llm4j.loom.graph.Kinds;
import io.github.llm4j.loom.graph.WorkflowGraph;
import io.github.llm4j.loom.wtest.StepResolver;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What one run, or several, did on the graph of their script: how often each statement ran, which
 * edges were taken, what was chosen where, and which statements none of the runs reached. Built
 * from what a run leaves behind (its steps in order and the choices made in them), so it needs no
 * model, tool or clock.
 *
 * <p>The journal holds no times, token counts or costs, so none are shown here.
 */
public final class RunOverlay {

    private RunOverlay() {}

    /** One choice made in a step: a condition and the way it went. */
    public record Choice(
            String step, String kind, String condition, Boolean result, String taken) {}

    /**
     * One run, as it left things.
     *
     * @param steps every step that started, in order
     * @param failedSteps the steps that failed
     * @param completed whether the run ran to its end
     */
    public record Run(
            String id,
            List<String> steps,
            List<Choice> choices,
            Set<String> failedSteps,
            boolean completed) {}

    /**
     * @param ran how many times the statement ran, over all runs (a loop's body runs once a round)
     * @param runs in how many runs it ran at least once
     * @param skipped in how many runs it did not run
     * @param failures how many times it failed
     */
    public record NodeStats(int ran, int runs, int skipped, int failures) {}

    /** The counts of one workflow's graph, by node id and by {@code from->to}. */
    public record WorkflowStats(Map<String, NodeStats> nodes, Map<String, Integer> edges) {
        public boolean taken(GraphEdge e) {
            return edges.getOrDefault(e.from() + "->" + e.to() + labelKey(e), 0) > 0;
        }
    }

    /** A choice made in the single run an overlay is of. */
    public record Decision(
            String workflow,
            String node,
            String kind,
            String condition,
            Boolean result,
            String taken) {}

    /** A statement no run reached. */
    public record Unreached(String workflow, String node, String label, int line) {}

    public record Overlay(
            List<String> runs,
            Map<String, WorkflowStats> workflows,
            List<Decision> decisions,
            List<Unreached> neverReached) {}

    static String labelKey(GraphEdge e) {
        return e.label() == null ? "" : "|" + e.label();
    }

    public static Overlay build(GraphResult graph, List<Run> runs) {
        StepResolver resolver = new StepResolver(graph);
        Map<String, WorkflowGraph> byName = new LinkedHashMap<>();
        graph.workflows().forEach(w -> byName.put(w.name(), w));

        Map<String, Map<String, int[]>> nodes =
                new LinkedHashMap<>(); // wf -> node -> ran, runs, fails
        Map<String, Map<String, Integer>> edges = new LinkedHashMap<>();
        for (WorkflowGraph w : graph.workflows()) {
            nodes.put(w.name(), new LinkedHashMap<>());
            edges.put(w.name(), new LinkedHashMap<>());
            for (GraphNode n : w.nodes()) nodes.get(w.name()).put(n.id(), new int[3]);
        }
        List<Decision> decisions = new ArrayList<>();
        for (Run run : runs) {
            Set<String> reached = new LinkedHashSet<>();
            Map<String, String> lastTaken = new HashMap<>();
            for (Choice c : run.choices()) {
                String key = resolver.resolve(c.step()).orElse(null);
                if (key == null) continue;
                lastTaken.put(key, c.taken());
                if (runs.size() == 1)
                    decisions.add(
                            new Decision(
                                    workflowOf(key),
                                    nodeOf(key),
                                    c.kind(),
                                    c.condition(),
                                    c.result(),
                                    c.taken()));
            }
            String previous = null;
            Set<String> startedWorkflows = new LinkedHashSet<>();
            Map<String, String> lastInWorkflow = new LinkedHashMap<>();
            for (String step : run.steps()) {
                String key = resolver.resolve(step).orElse(null);
                if (key == null) continue;
                String wf = workflowOf(key);
                String node = nodeOf(key);
                int[] counts = nodes.get(wf).get(node);
                if (counts == null) continue;
                counts[0]++;
                reached.add(key);
                if (startedWorkflows.add(wf)) bump(edges, byName.get(wf), Kinds.START, node, null);
                if (previous != null && workflowOf(previous).equals(wf))
                    link(edges, byName.get(wf), nodeOf(previous), node, lastTaken.get(previous));
                lastInWorkflow.put(wf, node);
                previous = key;
            }
            for (String failed : run.failedSteps()) {
                String key = resolver.resolve(failed).orElse(null);
                if (key == null) continue;
                int[] counts = nodes.get(workflowOf(key)).get(nodeOf(key));
                if (counts != null) counts[2]++;
            }
            if (run.completed())
                lastInWorkflow.forEach(
                        (wf, node) -> bump(edges, byName.get(wf), node, Kinds.END, null));
            for (String key : reached) nodes.get(workflowOf(key)).get(nodeOf(key))[1]++;
        }

        Map<String, WorkflowStats> out = new LinkedHashMap<>();
        List<Unreached> never = new ArrayList<>();
        int total = runs.size();
        for (WorkflowGraph w : graph.workflows()) {
            Map<String, NodeStats> stats = new LinkedHashMap<>();
            for (GraphNode n : w.nodes()) {
                int[] c = nodes.get(w.name()).get(n.id());
                if (Kinds.START.equals(n.kind()) || Kinds.END.equals(n.kind())) continue;
                stats.put(n.id(), new NodeStats(c[0], c[1], total - c[1], c[2]));
                if (c[1] == 0 && n.source() != null)
                    never.add(new Unreached(w.name(), n.id(), n.label(), n.source().line()));
            }
            out.put(w.name(), new WorkflowStats(stats, edges.get(w.name())));
        }
        return new Overlay(runs.stream().map(Run::id).toList(), out, decisions, never);
    }

    /** One step followed another: the edge between their nodes was taken, or a loop went round. */
    private static void link(
            Map<String, Map<String, Integer>> edges,
            WorkflowGraph w,
            String from,
            String to,
            String lastChoice) {
        List<GraphEdge> direct =
                w.edges().stream().filter(e -> e.from().equals(from) && e.to().equals(to)).toList();
        if (!direct.isEmpty()) {
            GraphEdge pick =
                    direct.stream()
                            .filter(e -> e.label() != null && e.label().equals(lastChoice))
                            .findFirst()
                            .orElse(direct.get(0));
            bump(edges, w, pick.from(), pick.to(), pick.label());
            return;
        }
        // a loop went round (or left): the last statement of the body goes back to the loop, which
        // goes on to the next statement
        for (GraphEdge again : w.edges()) {
            if (!again.from().equals(from) || !"again".equals(again.label())) continue;
            for (GraphEdge onward : w.edges()) {
                if (onward.from().equals(again.to()) && onward.to().equals(to)) {
                    bump(edges, w, again.from(), again.to(), again.label());
                    bump(edges, w, onward.from(), onward.to(), onward.label());
                    return;
                }
            }
        }
    }

    private static void bump(
            Map<String, Map<String, Integer>> edges,
            WorkflowGraph w,
            String from,
            String to,
            String label) {
        for (GraphEdge e : w.edges()) {
            if (!e.from().equals(from) || !e.to().equals(to)) continue;
            if (label != null && !label.equals(e.label())) continue;
            edges.get(w.name()).merge(from + "->" + to + labelKey(e), 1, Integer::sum);
            return;
        }
    }

    private static String workflowOf(String key) {
        return key.substring(0, key.indexOf('/'));
    }

    private static String nodeOf(String key) {
        return key.substring(key.indexOf('/') + 1);
    }
}
