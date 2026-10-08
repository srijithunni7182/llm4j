package io.github.llm4j.loom.graph;

/** A place in a script: the file and the 1-based line. */
public record SourceRef(String file, int line) {
}
