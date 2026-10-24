package io.github.llm4j.loom.wtest;

import io.github.llm4j.loom.graph.GraphNode;
import io.github.llm4j.loom.graph.GraphResult;
import io.github.llm4j.loom.graph.WorkflowGraph;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Which nodes of the script's graph the cases reached, by the ids {@code weave graph} uses. Node
 * coverage is the nodes reached over all nodes. A branch is a block of statements under an arm of
 * an alt, the body of a loop or a for each, or a handler; it is covered when one of its statements
 * ran. An arm with no statements has nothing to reach and is not counted.
 */
public final class Coverage {

    /** One node nobody reached, with what it is and how a case could get there. */
    public record Missed(String workflow, String id, int line, String label, String how) {}

    /** A block (a branch of a node) and whether it ran. */
    public record Branch(String workflow, String owner, String branch, boolean covered) {}

    private final GraphResult graph;
    private final Set<String> reached = new LinkedHashSet<>();

    public Coverage(GraphResult graph) {
        this.graph = graph;
    }

    /** Adds the step ids one case went through. */
    public void add(Collection<String> stepIds) {
        StepResolver resolver = new StepResolver(graph);
        for (String s : stepIds) resolver.resolve(s).ifPresent(reached::add);
    }

    /** The nodes reached, as {@code workflow/nodeId}. */
    public Set<String> reached() {
        return reached;
    }

    private static boolean real(GraphNode n) {
        return !n.kind().equals("start") && !n.kind().equals("end");
    }

    public int total() {
        int n = 0;
        for (WorkflowGraph w : graph.workflows()) for (GraphNode x : w.nodes()) if (real(x)) n++;
        return n;
    }

    public int covered() {
        int n = 0;
        for (WorkflowGraph w : graph.workflows())
            for (GraphNode x : w.nodes())
                if (real(x) && reached.contains(w.name() + "/" + x.id())) n++;
        return n;
    }

    public int percent() {
        return total() == 0 ? 100 : (int) Math.floor(100.0 * covered() / total());
    }

    /** Whether the node ran (the id may be given with or without its workflow). */
    public boolean ran(String workflow, String nodeId) {
        return reached.contains(workflow + "/" + nodeId);
    }

    /** The nodes nobody reached, in graph order, each with a hint for a case that would. */
    public List<Missed> missed() {
        List<Missed> out = new ArrayList<>();
        for (WorkflowGraph w : graph.workflows())
            for (GraphNode n : w.nodes()) {
                if (!real(n) || reached.contains(w.name() + "/" + n.id())) continue;
                out.add(
                        new Missed(
                                w.name(),
                                n.id(),
                                n.source() == null ? 0 : n.source().line(),
                                n.label(),
                                hint(w, n)));
            }
        return out;
    }

    /**
     * Every block under an alt arm, loop, for each or handler, and whether one of its steps ran.
     */
    public List<Branch> branches() {
        List<Branch> out = new ArrayList<>();
        for (WorkflowGraph w : graph.workflows()) {
            Set<String> seen = new LinkedHashSet<>();
            for (GraphNode n : w.nodes()) {
                if (!real(n) || n.parent() == null || n.branch() == null) continue;
                String key = n.parent() + ":" + n.branch();
                if (!seen.add(key)) continue;
                boolean covered = false;
                for (GraphNode m : w.nodes())
                    if (n.parent().equals(m.parent())
                            && n.branch().equals(m.branch())
                            && reached.contains(w.name() + "/" + m.id())) covered = true;
                out.add(new Branch(w.name(), n.parent(), n.branch(), covered));
            }
        }
        return out;
    }

    /** What a case would have to do to reach the node, in a line a person can act on. */
    private static String hint(WorkflowGraph w, GraphNode n) {
        if (n.parent() == null)
            return "an earlier step ends the workflow, or fails, before this one";
        GraphNode owner = null;
        for (GraphNode m : w.nodes()) if (m.id().equals(n.parent())) owner = m;
        String where = owner == null ? n.parent() : owner.label();
        return switch (n.branch()) {
            case "then" -> "make the condition of \"" + where + "\" true";
            case "else" -> "make the condition of \"" + where + "\" false";
            case "body" -> owner != null && owner.kind().equals("loop")
                    ? "the loop \"" + where + "\" must turn at least once"
                    : "\"" + where + "\" must run its body";
            case "exhausted" -> "\"" + where + "\" must reach its maximum without finishing";
            case "failure" -> "fail the step \""
                    + where
                    + "\" (fail: { "
                    + target(owner)
                    + ": { always: true, category: server_error } })";
            case "violation" -> "a guardrail \"" + where + "\" must be violated";
            default -> "reach the \"" + n.branch() + "\" block of \"" + where + "\"";
        };
    }

    private static String target(GraphNode owner) {
        if (owner == null) return "Agent";
        if (owner.agent() != null) return owner.agent();
        String l = owner.label();
        int space = l.indexOf(' ');
        return space > 0 ? l.substring(space + 1) : l;
    }

    /** Looks up the graph node by id or label for the {@code branches} assertion. */
    Optional<GraphNode> find(String workflow, String idOrLabel) {
        for (WorkflowGraph w : graph.workflows()) {
            if (!w.name().equals(workflow)) continue;
            for (GraphNode n : w.nodes())
                if (real(n) && (n.id().equals(idOrLabel) || n.label().equals(idOrLabel)))
                    return Optional.of(n);
        }
        return Optional.empty();
    }
}
