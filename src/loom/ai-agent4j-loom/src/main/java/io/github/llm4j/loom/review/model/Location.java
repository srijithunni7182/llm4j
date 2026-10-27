package io.github.llm4j.loom.review.model;

/**
 * Where a finding is. {@code line} is 1-based and 0 means the whole script; {@code column} and the
 * end position are 1-based and 0 when unknown. {@code nodeId} is the id of the graph node the
 * finding is about (the ids {@code weave graph} writes) or {@code null} when it is not about one
 * statement.
 */
public record Location(
        String file, int line, int column, int endLine, int endColumn, String nodeId) {

    /** A place with only a line: what most findings know. */
    public static Location at(String file, int line) {
        return new Location(file, line, 0, 0, 0, null);
    }

    /** The same place, tied to a graph node. */
    public Location withNode(String id) {
        return new Location(file, line, column, endLine, endColumn, id);
    }

    /** True when the finding is about the script as a whole. */
    public boolean wholeScript() {
        return line <= 0;
    }
}
