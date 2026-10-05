package io.github.llm4j.loom.graph;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.WorkflowDef;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/** Shared helpers for the graph tests. */
final class GraphTestSupport {

    private GraphTestSupport() {
    }

    static LoomScript parse(String source) {
        return new LoomParser(new Lexer(source).tokenize()).parseScript();
    }

    static LoomScript parseFile(String path) {
        try {
            return parse(Files.readString(Path.of(path)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static WorkflowGraph graphOf(String source, String workflow) {
        return graphOf(parse(source), workflow);
    }

    static WorkflowGraph graphOf(LoomScript script, String workflow) {
        WorkflowDef def = script.getWorkflows().stream().filter(w -> w.getName().equals(workflow)).findFirst().orElseThrow();
        return new GraphBuilder().build(def, "test.loom");
    }

    static WorkflowGraph graphOfFile(String path, String workflow) {
        return graphOf(parseFile(path), workflow);
    }

    static List<String> ids(WorkflowGraph graph) {
        return graph.nodes().stream().map(GraphNode::id).collect(Collectors.toList());
    }

    /** Edges as {@code from->to} or {@code from->to[label]}. */
    static List<String> edges(WorkflowGraph graph) {
        return graph.edges().stream()
                .map(e -> e.from() + "->" + e.to() + (e.label() == null ? "" : "[" + e.label() + "]"))
                .collect(Collectors.toList());
    }

    static GraphNode node(WorkflowGraph graph, String id) {
        return graph.nodes().stream().filter(n -> n.id().equals(id)).findFirst().orElseThrow();
    }
}
