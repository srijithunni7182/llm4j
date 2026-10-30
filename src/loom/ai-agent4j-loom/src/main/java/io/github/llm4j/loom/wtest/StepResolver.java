package io.github.llm4j.loom.wtest;

import io.github.llm4j.loom.graph.GraphNode;
import io.github.llm4j.loom.graph.GraphResult;
import io.github.llm4j.loom.graph.WorkflowGraph;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the id a run gives a step ({@code Main/s1/b0/r1.0}: the workflow, then the statement and
 * the block it is in, one part for each level) into the id {@code weave graph} gives the same
 * statement ({@code n6}). The two are built from the same statement lists in the same order, so the
 * i-th statement of a block in the run is the i-th node of that block in the graph.
 *
 * <p>A part names the kind of block with a letter: {@code s} the workflow's own statements, {@code
 * a} and {@code b} the then and else of an alt (and {@code b} the body of a for each), {@code
 * r}N{@code .} a round of a loop, {@code x} the {@code on_exhausted} handler, {@code f} the {@code
 * on_failure} handler, {@code g} and {@code v} a guardrail's body and violation handler, {@code p}N the
 * N-th branch (lane) of a parallel block, which is one statement. A part
 * that cannot be read stops the walk at the last node found, which is still a node the case
 * reached.
 */
public final class StepResolver {

    private static final Pattern PART = Pattern.compile("([a-z])(\\d+)|r\\d+\\.(\\d+)");
    private static final Pattern GENERATION = Pattern.compile("~\\d+");

    private final Map<String, List<GraphNode>> byWorkflow = new HashMap<>();

    public StepResolver(GraphResult graph) {
        for (WorkflowGraph w : graph.workflows()) byWorkflow.put(w.name(), w.nodes());
    }

    /**
     * The graph node id a run step id stands for, as {@code workflow/nodeId}, when it can be found.
     */
    public Optional<String> resolve(String stepId) {
        if (stepId == null || stepId.isEmpty()) return Optional.empty();
        // a called workflow's steps continue the caller's id after a '>' (Main/s3>AskPerson/s0):
        // inside it, the walk starts again from the callee's own statements
        String own = stepId.substring(stepId.lastIndexOf('>') + 1);
        String[] parts = GENERATION.matcher(own).replaceAll("").split("/");
        List<GraphNode> nodes = byWorkflow.get(parts[0]);
        if (nodes == null || parts.length < 2) return Optional.empty();
        GraphNode current = null;
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            // an item of a for each is a block of its own with the body inside it: the body's
            // statements are the children of the for each, whichever item they are for
            if (current != null && part.matches("e\\d+")) continue;
            Matcher m = PART.matcher(part);
            if (!m.matches()) break;
            String key = part.startsWith("r") && m.group(3) != null ? "r" : m.group(1);
            int index = Integer.parseInt(m.group(3) != null ? m.group(3) : m.group(2));
            GraphNode next;
            if (current == null) {
                if (!"s".equals(key)) break;
                next = child(nodes, null, null, index);
            } else {
                String branch = branch(key, current, index);
                if (branch == null) break;
                // a branch of a parallel block is a lane of its own holding the one statement
                next = child(nodes, current.id(), branch, key.equals("p") ? 0 : index);
            }
            if (next == null) break;
            current = next;
        }
        return current == null ? Optional.empty() : Optional.of(parts[0] + "/" + current.id());
    }

    private static String branch(String key, GraphNode owner, int index) {
        return switch (key) {
            case "a" -> "then";
            case "b" -> owner.kind().equals("foreach") ? "body" : "else";
            case "r" -> "body";
            case "x" -> "exhausted";
            case "f" -> "failure";
            case "g" -> "body";
            case "v" -> "violation";
            case "p" -> owner.kind().equals("parallel") ? "lane " + (index + 1) : null;
            default -> null;
        };
    }

    /** The i-th node of a block: those with this parent and branch, in the order of the graph. */
    private static GraphNode child(List<GraphNode> nodes, String parent, String branch, int index) {
        List<GraphNode> block = new ArrayList<>();
        for (GraphNode n : nodes) {
            if (n.kind().equals("start") || n.kind().equals("end")) continue;
            boolean sameParent = parent == null ? n.parent() == null : parent.equals(n.parent());
            boolean sameBranch = branch == null ? n.branch() == null : branch.equals(n.branch());
            if (sameParent && sameBranch) block.add(n);
        }
        return index < block.size() ? block.get(index) : null;
    }
}
