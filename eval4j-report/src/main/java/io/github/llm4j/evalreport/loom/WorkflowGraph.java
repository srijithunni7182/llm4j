package io.github.llm4j.evalreport.loom;

import io.github.llm4j.eval.export.WorkflowTrace;
import io.github.llm4j.loom.ast.WorkflowDef;
import io.github.llm4j.loom.graph.GraphBuilder;
import io.github.llm4j.loom.graph.GraphEdge;
import io.github.llm4j.loom.graph.GraphNode;
import io.github.llm4j.loom.graph.Kinds;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The structure of a Loom workflow for the report: {@code start}, one node per statement in pre-order
 * ({@code n1}, {@code n2}, …, stable for a given script), and {@code end}.
 *
 * <p>The structure itself comes from {@link GraphBuilder}, the same builder the editor graph uses, so the report
 * and the editor never disagree. This class only maps it to the neutral {@link WorkflowTrace} shape and works out,
 * for each statement, where it sits (the enclosing {@code alt} and loop nodes) so a trace can be placed on it.
 */
public final class WorkflowGraph {

    public static final String START = "start";
    public static final String END = "end";

    /** Handler blocks hang off their owner; they are not branches the path has to enter. */
    private static final Set<String> HANDLER_BRANCHES = Set.of("failure", "exhausted", "violation", "still fails", "blocked");

    /** Node kinds whose block a statement can be inside of, for placing a trace. */
    private static final Set<String> BLOCK_KINDS = Set.of(Kinds.ALT, Kinds.LOOP, Kinds.FOREACH);

    /** A statement node plus where it sits: the enclosing alt/loop nodes, outermost first. */
    record Slot(
            WorkflowTrace.Node node,
            List<String> ancestors,
            Map<String, String> branches,
            Set<String> agents,
            String task) {}

    final List<WorkflowTrace.Node> nodes = new ArrayList<>();
    final List<WorkflowTrace.Edge> edges = new ArrayList<>();
    final List<Slot> slots = new ArrayList<>();

    private WorkflowGraph() {}

    public static WorkflowGraph of(WorkflowDef workflow) {
        return of(workflow, agent -> null);
    }

    /** As {@link #of(WorkflowDef)}, with {@code promptLabels} giving the prompt (id and version) an agent runs, shown on its steps. */
    public static WorkflowGraph of(WorkflowDef workflow, java.util.function.Function<String, String> promptLabels) {
        io.github.llm4j.loom.graph.WorkflowGraph built = new GraphBuilder().build(workflow, "");
        Map<String, GraphNode> byId = new HashMap<>();
        built.nodes().forEach(n -> byId.put(n.id(), n));

        WorkflowGraph g = new WorkflowGraph();
        for (GraphNode graphNode : built.nodes()) {
            String prompt = graphNode.agent() == null ? null : promptLabels.apply(graphNode.agent());
            GraphNode node = prompt == null ? graphNode : graphNode.withAttr("prompt", prompt);
            WorkflowTrace.Node mapped = new WorkflowTrace.Node(
                    node.id(), node.kind(), node.label(), node.agent(), node.bound(), withPlacement(node));
            g.nodes.add(mapped);
            if (!node.kind().equals(Kinds.START) && !node.kind().equals(Kinds.END)) {
                g.slots.add(slot(mapped, node, byId));
            }
        }
        for (GraphEdge edge : built.edges()) {
            g.edges.add(new WorkflowTrace.Edge(edge.from(), edge.to(), edge.label()));
        }
        return g;
    }

    public List<WorkflowTrace.Node> nodes() {
        return List.copyOf(nodes);
    }

    public List<WorkflowTrace.Edge> edges() {
        return List.copyOf(edges);
    }

    /**
     * The node's settings plus where it sits ({@code parent}, {@code branch}). A trace node has no field for the
     * block it is in, and the report needs it to fold large blocks, so it travels with the settings.
     */
    private static Map<String, Object> withPlacement(GraphNode node) {
        if (node.parent() == null) {
            return node.attrs();
        }
        Map<String, Object> merged = new LinkedHashMap<>(node.attrs());
        merged.put("parent", node.parent());
        if (node.branch() != null) {
            merged.put("branch", node.branch());
        }
        return merged;
    }

    /** Walks up from a node through the blocks it is inside of, outermost first. */
    private static Slot slot(WorkflowTrace.Node mapped, GraphNode node, Map<String, GraphNode> byId) {
        List<String> ancestors = new ArrayList<>();
        Map<String, String> branches = new HashMap<>();
        GraphNode child = node;
        while (child.parent() != null) {
            GraphNode parent = byId.get(child.parent());
            if (BLOCK_KINDS.contains(parent.kind()) && !HANDLER_BRANCHES.contains(child.branch())) {
                ancestors.add(0, parent.id());
                if (Kinds.ALT.equals(parent.kind())) {
                    branches.put(parent.id(), child.branch());
                }
            }
            child = parent;
        }
        Set<String> agents = new LinkedHashSet<>();
        if (node.attrs().get("agents") instanceof List<?> list) {
            list.forEach(a -> agents.add(String.valueOf(a)));
        }
        Object task = node.attrs().get("task");
        return new Slot(mapped, ancestors, branches, agents, task == null ? null : String.valueOf(task));
    }
}
