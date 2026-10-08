package io.github.llm4j.loom.graph;

/** Draws a workflow graph as Mermaid {@code flowchart} text, for docs and issues. */
public final class MermaidRenderer {

    private MermaidRenderer() {
    }

    public static String render(WorkflowGraph graph) {
        StringBuilder out = new StringBuilder("flowchart TD\n");
        for (GraphNode node : graph.nodes()) {
            out.append("  ").append(node(node)).append('\n');
        }
        for (GraphEdge edge : graph.edges()) {
            boolean rewind = "rewind".equals(edge.label());
            out.append("  ").append(id(edge.from())).append(rewind ? " -.->" : " -->");
            if (edge.label() != null) {
                out.append('|').append(escape(edge.label())).append('|');
            }
            out.append(' ').append(id(edge.to())).append('\n');
        }
        return out.toString();
    }

    private static String node(GraphNode node) {
        String id = id(node.id());
        String label = "\"" + escape(node.label()) + "\"";
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
