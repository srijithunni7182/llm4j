package io.github.llm4j.loom.inspect.map;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.WorkflowDef;
import io.github.llm4j.loom.graph.GraphBuilder;
import io.github.llm4j.loom.graph.GraphNode;
import io.github.llm4j.loom.graph.Kinds;
import io.github.llm4j.loom.graph.WorkflowGraph;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A script's workflows as plain maps for the page, each node carrying {@code path}: the normalized step id the executor reports
 * while that statement runs (see {@code StepPaths}), so the page can colour a node by what the run is doing. Nothing is run.
 *
 * <p>A statement's path is its block's path plus its position in the block. The block keys are the executor's own: {@code s} for a
 * workflow, {@code a}/{@code b} for the two sides of an {@code alt}, {@code r.} for a loop body (one place for every round), {@code b}
 * for a {@code for each}, {@code g} for a guardrail, and {@code x}, {@code v}, {@code f} for the exhausted, violation and failure
 * handlers. Blocks with no key here (a rewind's handlers) have no path and are drawn without a state.
 */
public final class GraphView {

    private GraphView() { }

    public static Map<String, Object> of(LoomScript script) {
        GraphBuilder builder = new GraphBuilder();
        List<Object> workflows = new ArrayList<>();
        for (WorkflowDef def : script.getWorkflows()) {
            workflows.add(workflow(builder.build(def, "")));
        }
        return Map.of("workflows", workflows);
    }

    private static Map<String, Object> workflow(WorkflowGraph g) {
        Map<String, GraphNode> byId = new HashMap<>();
        for (GraphNode n : g.nodes()) byId.put(n.id(), n);
        Map<String, String> paths = new HashMap<>();
        Map<String, Integer> next = new HashMap<>();
        List<Object> nodes = new ArrayList<>();
        for (GraphNode n : g.nodes()) {
            if (Kinds.START.equals(n.kind()) || Kinds.END.equals(n.kind())) continue;
            String base;
            if (n.parent() == null) {
                base = g.name() + "/s";
            } else {
                String owner = paths.get(n.parent());
                String key = key(byId.get(n.parent()), n.branch());
                base = owner == null || key == null ? null : owner + "/" + key;
            }
            String path = base == null ? null : base + (next.merge(base, 1, Integer::sum) - 1); // positions count from 0
            if (path != null) paths.put(n.id(), path);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.id());
            m.put("kind", n.kind());
            m.put("label", n.label());
            if (n.agent() != null) m.put("agent", n.agent());
            if (n.parent() != null) m.put("parent", n.parent());
            if (n.branch() != null) m.put("branch", n.branch());
            if (path != null) m.put("path", path);
            nodes.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", g.name());
        out.put("nodes", nodes);
        return out;
    }

    private static String key(GraphNode owner, String branch) {
        if (owner == null || branch == null) return null;
        return switch (branch) {
            case "then" -> "a";
            case "else" -> "b";
            case "exhausted" -> "x";
            case "violation" -> "v";
            case "failure" -> "f";
            case "body" -> switch (owner.kind()) {
                case Kinds.LOOP -> "r.";
                case Kinds.FOREACH -> "b";
                case Kinds.GUARDRAIL -> "g";
                default -> null;
            };
            default -> null;
        };
    }
}
