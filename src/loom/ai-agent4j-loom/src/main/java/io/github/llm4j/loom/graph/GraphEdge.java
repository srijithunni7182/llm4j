package io.github.llm4j.loom.graph;

/** A control-flow link between two nodes; {@code label} is {@code null} for plain sequence. */
public record GraphEdge(String from, String to, String label) {
}
