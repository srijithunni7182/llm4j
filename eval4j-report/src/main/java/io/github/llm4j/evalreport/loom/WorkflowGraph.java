package io.github.llm4j.evalreport.loom;

import io.github.llm4j.eval.export.WorkflowTrace;
import io.github.llm4j.loom.ast.AltStmt;
import io.github.llm4j.loom.ast.CheckpointStmt;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.HandoffStmt;
import io.github.llm4j.loom.ast.HumanPromptStmt;
import io.github.llm4j.loom.ast.LoopStmt;
import io.github.llm4j.loom.ast.ParallelStmt;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.ast.WorkflowDef;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the structure of a Loom workflow for the report: {@code start}, one node per statement in
 * pre-order ({@code n1}, {@code n2}, …, stable for a given script), and {@code end}. Edges follow
 * control flow: {@code alt} has a labelled edge into each branch, a {@code loop} has a back-edge
 * from its body and an exit edge.
 */
public final class WorkflowGraph {

    public static final String START = "start";
    public static final String END = "end";

    /** A statement node plus where it sits: the enclosing alt/loop nodes, outermost first. */
    record Slot(
            WorkflowTrace.Node node,
            List<String> ancestors,
            java.util.Map<String, String> branches,
            java.util.Set<String> agents) {}

    final List<WorkflowTrace.Node> nodes = new ArrayList<>();
    final List<WorkflowTrace.Edge> edges = new ArrayList<>();
    final List<Slot> slots = new ArrayList<>();
    private int counter;

    public static WorkflowGraph of(WorkflowDef workflow) {
        WorkflowGraph g = new WorkflowGraph();
        g.nodes.add(new WorkflowTrace.Node(START, "start", "Start", null, null));
        List<String> exits = new ArrayList<>(List.of(START));
        exits = g.block(workflow.getStatements(), exits, List.of(), java.util.Map.of(), null);
        g.nodes.add(new WorkflowTrace.Node(END, "end", "End", null, null));
        for (String e : exits) {
            g.edges.add(new WorkflowTrace.Edge(e, END, null));
        }
        return g;
    }

    public List<WorkflowTrace.Node> nodes() {
        return List.copyOf(nodes);
    }

    public List<WorkflowTrace.Edge> edges() {
        return List.copyOf(edges);
    }

    /** Lays out a statement list; returns the nodes control leaves from. */
    private List<String> block(
            List<Statement> statements,
            List<String> entries,
            List<String> ancestors,
            java.util.Map<String, String> branches,
            String entryLabel) {
        List<String> current = entries;
        String label = entryLabel;
        for (Statement st : statements) {
            String id = "n" + (++counter);
            WorkflowTrace.Node node = nodeFor(id, st);
            nodes.add(node);
            slots.add(new Slot(node, ancestors, branches, agentsOf(st)));
            for (String from : current) {
                edges.add(new WorkflowTrace.Edge(from, id, label));
            }
            label = null;
            List<String> inner = new ArrayList<>(ancestors);
            if (st instanceof AltStmt alt) {
                inner.add(id);
                java.util.Map<String, String> thenB = new java.util.HashMap<>(branches);
                thenB.put(id, "then");
                java.util.Map<String, String> elseB = new java.util.HashMap<>(branches);
                elseB.put(id, "else");
                List<String> thenExit =
                        alt.getIfBranch().isEmpty()
                                ? List.of(id)
                                : block(alt.getIfBranch(), List.of(id), inner, thenB, "then");
                List<String> elseExit =
                        alt.getElseBranch().isEmpty()
                                ? List.of(id)
                                : block(alt.getElseBranch(), List.of(id), inner, elseB, "else");
                List<String> exits = new ArrayList<>(thenExit);
                exits.addAll(elseExit);
                current = exits;
            } else if (st instanceof LoopStmt loop) {
                inner.add(id);
                List<String> bodyExit = block(loop.getBody(), List.of(id), inner, branches, null);
                for (String b : bodyExit) {
                    if (!b.equals(id)) {
                        edges.add(new WorkflowTrace.Edge(b, id, "again"));
                    }
                }
                current = List.of(id);
            } else {
                current = List.of(id);
            }
        }
        return current;
    }

    /** The agents a parallel block delegates to: its node stands for the whole round. */
    private static java.util.Set<String> agentsOf(Statement st) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (st instanceof ParallelStmt p) {
            for (Statement c : p.getBody()) {
                if (c instanceof DelegateStmt d) {
                    out.add(d.getTargetAgent());
                }
            }
        }
        return out;
    }

    private static WorkflowTrace.Node nodeFor(String id, Statement st) {
        if (st instanceof DelegateStmt d) {
            return new WorkflowTrace.Node(
                    id, "delegate", "delegate " + d.getTargetAgent(), d.getTargetAgent(), null);
        }
        if (st instanceof HandoffStmt h) {
            return new WorkflowTrace.Node(
                    id, "handoff", "handoff " + h.getTargetAgent(), h.getTargetAgent(), null);
        }
        if (st instanceof AltStmt a) {
            return new WorkflowTrace.Node(id, "alt", cut(a.getCondition()) + "?", null, null);
        }
        if (st instanceof LoopStmt l) {
            return new WorkflowTrace.Node(
                    id,
                    "loop",
                    "loop until " + cut(l.getCondition()),
                    null,
                    l.getMaxIterations() > 0 ? l.getMaxIterations() : null);
        }
        if (st instanceof HumanPromptStmt) {
            return new WorkflowTrace.Node(id, "human_prompt", "ask a person", null, null);
        }
        if (st instanceof CheckpointStmt c) {
            return new WorkflowTrace.Node(
                    id, "checkpoint", "checkpoint " + c.getName(), null, null);
        }
        if (st instanceof ParallelStmt) {
            return new WorkflowTrace.Node(
                    id, "parallel", "in parallel: " + String.join(", ", agentsOf(st)), null, null);
        }
        return new WorkflowTrace.Node(
                id,
                "statement",
                st.getClass()
                        .getSimpleName()
                        .replace("Stmt", "")
                        .toLowerCase(java.util.Locale.ROOT),
                null,
                null);
    }

    private static String cut(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }
}
