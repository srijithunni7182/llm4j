package io.github.llm4j.loom.graph;

/** A problem found while building a graph. The graph is still produced; the problem is reported with it. */
public record Diagnostic(Severity severity, String file, int line, String message) {

    public enum Severity {
        ERROR,
        WARNING;

        public String word() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public static Diagnostic error(String file, int line, String message) {
        return new Diagnostic(Severity.ERROR, file, line, message);
    }

    public static Diagnostic warning(String file, int line, String message) {
        return new Diagnostic(Severity.WARNING, file, line, message);
    }
}
