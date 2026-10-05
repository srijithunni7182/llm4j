package io.github.llm4j.loom.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the workflow each {@code call} node names. Callees are never expanded into the caller; a call node only
 * records where its callee is defined, so recursion cannot loop.
 *
 * <p>When two files define the same workflow name, the first one in run order wins, which is what the harness does:
 * imports are merged before the importing file's own definitions and the first match is used. The loser is reported.
 */
public final class CallResolver {

    /** The graphs with their calls resolved, and what was found wrong on the way. */
    public record Resolution(List<WorkflowGraph> workflows, List<Diagnostic> diagnostics) {
    }

    /** @param inRunOrder every workflow graph, files in run order and workflows in the order they are written */
    public Resolution resolve(List<WorkflowGraph> inRunOrder) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        Map<String, WorkflowGraph> winners = new LinkedHashMap<>();
        for (WorkflowGraph graph : inRunOrder) {
            WorkflowGraph winner = winners.putIfAbsent(graph.name(), graph);
            if (winner != null) {
                diagnostics.add(Diagnostic.warning(
                        graph.file(),
                        graph.line(),
                        "Workflow " + graph.name() + " is also defined in " + winner.file() + ":" + winner.line()
                                + ". Calls use that one, the first in run order."));
            }
        }
        List<WorkflowGraph> resolved = new ArrayList<>();
        for (WorkflowGraph graph : inRunOrder) {
            resolved.add(graph.withNodes(resolveNodes(graph, winners, diagnostics)));
        }
        return new Resolution(resolved, diagnostics);
    }

    private static List<GraphNode> resolveNodes(
            WorkflowGraph graph, Map<String, WorkflowGraph> winners, List<Diagnostic> diagnostics) {
        List<GraphNode> nodes = new ArrayList<>();
        for (GraphNode node : graph.nodes()) {
            if (node.call() == null) {
                nodes.add(node);
                continue;
            }
            WorkflowGraph callee = winners.get(node.call().workflow());
            if (callee == null) {
                nodes.add(node.withCall(node.call(), true));
                diagnostics.add(Diagnostic.error(
                        graph.file(),
                        node.source() == null ? 0 : node.source().line(),
                        "call " + node.call().workflow() + ": no workflow with that name in this file or its imports."));
            } else {
                nodes.add(node.withCall(node.call().resolvedIn(callee.file()), false));
            }
        }
        return nodes;
    }
}
