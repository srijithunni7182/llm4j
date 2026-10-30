package io.github.llm4j.loom.graph;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Draws a workflow graph as Mermaid {@code flowchart} text, for docs and issues. */
public final class MermaidRenderer {

    private MermaidRenderer() {}

    public static String render(WorkflowGraph graph) {
        return render(graph, null);
    }

    /** What a run (or runs) did, to draw on the graph. */
    public interface Marks {
        /** The count to show beside the node, or a negative number for none. */
        int times(GraphNode node);

        /** Whether the node ran (bold) or did not (faded). */
        boolean ran(GraphNode node);

        /** Whether the edge was taken. */
        boolean taken(GraphEdge edge);
    }

    /** As {@link #render(WorkflowGraph)}, with the path taken drawn bold and the rest dotted. */
    public static String render(WorkflowGraph graph, Marks marks) {
        StringBuilder out = new StringBuilder("flowchart TD\n");
        List<String> ran = new ArrayList<>();
        List<String> idle = new ArrayList<>();
        Set<String> inLanes = new HashSet<>();
        for (GraphNode node : graph.nodes())
            for (Map<String, Object> lane : lanes(node))
                for (Object member : (List<?>) lane.get("nodes")) inLanes.add(String.valueOf(member));
        for (GraphNode node : graph.nodes()) {
            if (!inLanes.contains(node.id())) {
                out.append("  ").append(node(node, marks)).append('\n');
                lanesOf(out, graph, node, marks, 1);
            }
            if (marks == null) continue;
            if (Kinds.START.equals(node.kind()) || Kinds.END.equals(node.kind())) continue;
            (marks.ran(node) ? ran : idle).add(id(node.id()));
        }
        for (GraphEdge edge : graph.edges()) {
            boolean rewind = "rewind".equals(edge.label()) || "stopped".equals(edge.label());
            boolean taken = marks != null && marks.taken(edge);
            String arrow = marks != null ? (taken ? " ==>" : " -.->") : rewind ? " -.->" : " -->";
            out.append("  ").append(id(edge.from())).append(arrow);
            if (edge.label() != null) {
                out.append('|').append(escape(edge.label())).append('|');
            }
            out.append(' ').append(id(edge.to())).append('\n');
        }
        if (marks != null) {
            out.append("  classDef ran stroke-width:3px\n");
            out.append("  classDef idle stroke-dasharray:4 3,opacity:0.5\n");
            if (!ran.isEmpty())
                out.append("  class ").append(String.join(",", ran)).append(" ran\n");
            if (!idle.isEmpty())
                out.append("  class ").append(String.join(",", idle)).append(" idle\n");
        }
        return out.toString();
    }

    /** A parallel node's lanes, as the graph model gives them; none for any other node. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> lanes(GraphNode node) {
        Object lanes = node.attrs().get("lanes");
        return lanes instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    /**
     * Draws each lane of a parallel node as a subgraph holding the lane's steps. A parallel block
     * inside a lane is drawn as a subgraph inside that lane, and its steps are declared once, there.
     */
    private static void lanesOf(StringBuilder out, WorkflowGraph graph, GraphNode owner, Marks marks, int depth) {
        String pad = "  ".repeat(depth);
        int number = 0;
        for (Map<String, Object> lane : lanes(owner)) {
            out.append(pad)
                    .append("subgraph ")
                    .append(id(owner.id()))
                    .append("_lane")
                    .append(++number)
                    .append("[\"")
                    .append(escape(String.valueOf(lane.get("label"))))
                    .append("\"]\n");
            Set<String> inner = new HashSet<>();
            for (Object member : (List<?>) lane.get("nodes"))
                for (GraphNode node : graph.nodes())
                    if (node.id().equals(member) && !node.id().equals(owner.id()))
                        for (Map<String, Object> nested : lanes(node))
                            for (Object deeper : (List<?>) nested.get("nodes")) inner.add(String.valueOf(deeper));
            for (Object member : (List<?>) lane.get("nodes"))
                for (GraphNode node : graph.nodes())
                    if (node.id().equals(member) && !inner.contains(node.id())) {
                        out.append(pad).append("  ").append(node(node, marks)).append('\n');
                        lanesOf(out, graph, node, marks, depth + 1);
                    }
            out.append(pad).append("end\n");
        }
    }

    private static String node(GraphNode node, Marks marks) {
        String id = id(node.id());
        int times = marks == null ? -1 : marks.times(node);
        String label = "\"" + escape(node.label()) + (times > 1 ? " ×" + times : "") + "\"";
        switch (node.kind()) {
            case Kinds.START:
            case Kinds.END:
                return id + "([" + label + "])";
            case Kinds.ALT:
                return id + "{{" + label + "}}";
            case Kinds.LOOP:
            case Kinds.FOREACH:
                return id + "(" + label + ")";
            case Kinds.HUMAN_PROMPT:
                return id + "[/" + label + "/]";
            case Kinds.CALL:
                return id + "[[" + label + "]]";
            case Kinds.CHECKPOINT:
                return id + ">" + label + "]";
            default:
                return id + "[" + label + "]";
        }
    }

    /** {@code end} is a reserved word in Mermaid, so the end node gets a trailing underscore. */
    private static String id(String id) {
        return Kinds.END.equals(id) ? "end_" : id.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private static String escape(String text) {
        return text.replace("\"", "#quot;").replace("\n", " ");
    }
}
