package io.github.llm4j.loom.overlay;

import io.github.llm4j.loom.graph.GraphEdge;
import io.github.llm4j.loom.graph.GraphNode;
import io.github.llm4j.loom.graph.MermaidRenderer;
import io.github.llm4j.loom.graph.WorkflowGraph;

/** A workflow's graph in Mermaid with the path the runs took drawn bold and the rest dotted. */
public final class OverlayMermaid {

    private OverlayMermaid() {}

    public static String render(WorkflowGraph graph, RunOverlay.WorkflowStats stats) {
        return MermaidRenderer.render(
                graph,
                new MermaidRenderer.Marks() {
                    @Override
                    public int times(GraphNode node) {
                        var n = stats.nodes().get(node.id());
                        return n == null ? -1 : n.ran();
                    }

                    @Override
                    public boolean ran(GraphNode node) {
                        var n = stats.nodes().get(node.id());
                        return n != null && n.ran() > 0;
                    }

                    @Override
                    public boolean taken(GraphEdge edge) {
                        return stats.taken(edge);
                    }
                });
    }
}
