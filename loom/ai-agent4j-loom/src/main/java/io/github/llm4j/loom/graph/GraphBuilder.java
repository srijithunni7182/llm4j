package io.github.llm4j.loom.graph;

import io.github.llm4j.loom.ast.AltStmt;
import io.github.llm4j.loom.ast.CheckpointStmt;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.ForEachStmt;
import io.github.llm4j.loom.ast.GuardrailStmt;
import io.github.llm4j.loom.ast.LoopStmt;
import io.github.llm4j.loom.ast.RewindStmt;
import io.github.llm4j.loom.ast.RunStmt;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.ast.WorkflowDef;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the graph of one workflow: {@code start}, one node per statement in pre-order, {@code end}, and the
 * control-flow edges between them.
 *
 * <ul>
 *   <li>{@code alt} has a {@code then} and an {@code else} edge into its branches; both exits join the next step.
 *   <li>{@code loop} and {@code for each} have an edge into the body, an {@code again} edge back from it, and a
 *       {@code done} edge to the next step.
 *   <li>{@code parallel} is one node standing for the whole round.
 *   <li>Handler blocks ({@code on_failure}, {@code on_exhausted}, {@code on_violation}, a rewind's
 *       {@code if still fails} and {@code if blocked}) hang off their owner on a labelled edge and rejoin the main
 *       path. They are numbered after the main path, so adding a handler never changes the id of another step.
 * </ul>
 */
public final class GraphBuilder {

    private final NodeDescriber describer;

    /** @param decisionLevels decision name to the level it starts at, shown on {@code decide} nodes */
    public GraphBuilder(Map<String, String> decisionLevels) {
        this.describer = new NodeDescriber(decisionLevels);
    }

    public GraphBuilder() {
        this(Map.of());
    }

    public WorkflowGraph build(WorkflowDef workflow, String file) {
        return new Build(workflow, file).run();
    }

    /** Where control leaves a node, and the label of the edge it leaves on. */
    private record Exit(String from, String label) {
    }

    /** A handler block waiting to be laid out once the main path is done. */
    private record Pending(String token, String owner, String branch, String edgeLabel, List<Statement> statements) {
    }

    private final class Build {
        private final WorkflowDef workflow;
        private final String file;
        private final List<GraphNode> nodes = new ArrayList<>();
        private final List<GraphEdge> edges = new ArrayList<>();
        private final Deque<Pending> pending = new ArrayDeque<>();
        /** Handler token to where control leaves the handler block. */
        private final Map<String, List<Exit>> handlerExits = new HashMap<>();
        private final Map<String, String> checkpoints = new HashMap<>();
        private int counter;

        Build(WorkflowDef workflow, String file) {
            this.workflow = workflow;
            this.file = file;
        }

        WorkflowGraph run() {
            nodes.add(marker(Kinds.START, "Start"));
            List<Exit> exits = block(workflow.getStatements(), List.of(new Exit(Kinds.START, null)), null, null);
            while (!pending.isEmpty()) {
                layOut(pending.poll());
            }
            nodes.add(marker(Kinds.END, "End"));
            List<GraphEdge> expanded = new ArrayList<>();
            for (GraphEdge edge : edges) {
                for (Exit from : expand(new Exit(edge.from(), edge.label()))) {
                    expanded.add(new GraphEdge(from.from(), edge.to(), edge.label() != null ? edge.label() : from.label()));
                }
            }
            for (Exit exit : exits) {
                for (Exit from : expand(exit)) {
                    expanded.add(new GraphEdge(from.from(), Kinds.END, from.label()));
                }
            }
            return new WorkflowGraph(
                    workflow.getName(),
                    file,
                    workflow.getLine(),
                    workflow.getParameters(),
                    nodes,
                    new ArrayList<>(new LinkedHashSet<>(expanded)));
        }

        private GraphNode marker(String kind, String label) {
            return new GraphNode(kind, kind, label, null, null, null, null, false, null, null, Map.of());
        }

        /** Lays out a statement list; returns where control leaves it. */
        private List<Exit> block(List<Statement> statements, List<Exit> entries, String parent, String branch) {
            List<Exit> current = entries;
            for (Statement statement : statements) {
                String id = "n" + (++counter);
                nodes.add(describer.describe(id, statement, file).withPlacement(parent, branch));
                for (Exit from : current) {
                    edges.add(new GraphEdge(from.from(), id, from.label()));
                }
                current = leave(statement, id);
            }
            return current;
        }

        /** Lays out what is inside a statement and returns where control leaves it. */
        private List<Exit> leave(Statement statement, String id) {
            List<Exit> exits = new ArrayList<>();
            if (statement instanceof AltStmt alt) {
                exits.addAll(branch(alt.getIfBranch(), id, "then"));
                exits.addAll(branch(alt.getElseBranch(), id, "else"));
            } else if (statement instanceof LoopStmt loop) {
                exits.addAll(repeat(loop.getBody(), id));
                exits.add(new Exit(id, "done"));
                defer(exits, id, "exhausted", "exhausted", loop.getOnExhausted());
            } else if (statement instanceof ForEachStmt each) {
                exits.addAll(repeat(each.getBody(), id));
                exits.add(new Exit(id, "done"));
                defer(exits, id, "exhausted", "exhausted", each.getOnExhausted());
            } else if (statement instanceof GuardrailStmt guard) {
                List<Exit> body = block(guard.getBody(), List.of(new Exit(id, null)), id, "body");
                exits.addAll(guard.getBody().isEmpty() ? List.of(new Exit(id, null)) : body);
                defer(exits, id, "violation", "violation", guard.getOnViolation());
            } else if (statement instanceof DelegateStmt delegate) {
                exits.add(new Exit(id, null));
                defer(exits, id, "failure", "failure", delegate.getOnFailure());
            } else if (statement instanceof RunStmt run) {
                exits.add(new Exit(id, null));
                defer(exits, id, "failure", "failure", run.getOnFailure());
            } else if (statement instanceof CheckpointStmt checkpoint) {
                checkpoints.putIfAbsent(checkpoint.getName(), id);
                exits.add(new Exit(id, null));
            } else if (statement instanceof RewindStmt rewind) {
                exits.add(new Exit(id, null));
                rewindBack(rewind, id);
                defer(exits, id, "still fails", "still fails", rewind.getIfStillFails());
                defer(exits, id, "blocked", "blocked", rewind.getIfBlocked());
            } else {
                exits.add(new Exit(id, null));
            }
            return distinct(exits);
        }

        private List<Exit> branch(List<Statement> statements, String owner, String name) {
            if (statements == null || statements.isEmpty()) {
                return List.of(new Exit(owner, null));
            }
            return block(statements, List.of(new Exit(owner, name)), owner, name);
        }

        /** A loop body: into it from the owner, and an {@code again} edge back from where it ends. */
        private List<Exit> repeat(List<Statement> body, String owner) {
            List<Exit> bodyExits = block(body, List.of(new Exit(owner, null)), owner, "body");
            for (Exit exit : bodyExits) {
                if (!exit.from().equals(owner)) {
                    edges.add(new GraphEdge(exit.from(), owner, "again"));
                }
            }
            return List.of();
        }

        /** Draws the way back to the checkpoint a rewind names; a rewind to a checkpoint that is not there is marked. */
        private void rewindBack(RewindStmt rewind, String id) {
            String target = checkpoints.get(rewind.getTarget());
            if (target == null) {
                int last = nodes.size() - 1;
                nodes.set(last, nodes.get(last).withUnresolved(true));
            } else {
                edges.add(new GraphEdge(id, target, "rewind"));
            }
        }

        /** Registers a handler block to lay out later; control leaves it into the next step too. */
        private void defer(List<Exit> exits, String owner, String branch, String edgeLabel, List<Statement> statements) {
            if (statements == null || statements.isEmpty()) {
                return;
            }
            String token = "~handler@" + owner + ":" + branch;
            pending.add(new Pending(token, owner, branch, edgeLabel, statements));
            exits.add(new Exit(token, null));
        }

        private void layOut(Pending handler) {
            List<Exit> exits = block(
                    handler.statements(),
                    List.of(new Exit(handler.owner(), handler.edgeLabel())),
                    handler.owner(),
                    handler.branch());
            handlerExits.put(handler.token(), exits);
        }

        /** Replaces a handler token by where control actually leaves that handler. */
        private List<Exit> expand(Exit exit) {
            List<Exit> real = handlerExits.get(exit.from());
            if (real == null) {
                return List.of(exit);
            }
            List<Exit> out = new ArrayList<>();
            for (Exit inner : real) {
                out.addAll(expand(inner));
            }
            return out;
        }

        private List<Exit> distinct(List<Exit> exits) {
            Set<Exit> seen = new LinkedHashSet<>(exits);
            return new ArrayList<>(seen);
        }
    }
}
