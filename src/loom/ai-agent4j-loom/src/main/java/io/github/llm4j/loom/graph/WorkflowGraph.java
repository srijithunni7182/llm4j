package io.github.llm4j.loom.graph;

import java.util.List;

/**
 * The structure of one workflow: {@code start}, one node per statement in pre-order ({@code n1},
 * {@code n2}, …, stable for a given script) and {@code end}, joined by control-flow edges.
 */
public record WorkflowGraph(
        String name,
        String file,
        int line,
        List<String> params,
        List<GraphNode> nodes,
        List<GraphEdge> edges,
        List<String> exposedAs) {

    public WorkflowGraph {
        params = List.copyOf(params);
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
        exposedAs = exposedAs == null ? List.of() : List.copyOf(exposedAs);
    }

    /** A workflow that no {@code expose} block serves. */
    public WorkflowGraph(
            String name,
            String file,
            int line,
            List<String> params,
            List<GraphNode> nodes,
            List<GraphEdge> edges) {
        this(name, file, line, params, nodes, edges, List.of());
    }

    public WorkflowGraph withNodes(List<GraphNode> newNodes) {
        return new WorkflowGraph(name, file, line, params, newNodes, edges, exposedAs);
    }
}
