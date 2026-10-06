package io.github.llm4j.loom.graph;

import java.util.List;

/**
 * The structure of one workflow: {@code start}, one node per statement in pre-order ({@code n1}, {@code n2}, …,
 * stable for a given script) and {@code end}, joined by control-flow edges.
 */
public record WorkflowGraph(
        String name,
        String file,
        int line,
        List<String> params,
        List<GraphNode> nodes,
        List<GraphEdge> edges) {

    public WorkflowGraph {
        params = List.copyOf(params);
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }

    public WorkflowGraph withNodes(List<GraphNode> newNodes) {
        return new WorkflowGraph(name, file, line, params, newNodes, edges);
    }
}
