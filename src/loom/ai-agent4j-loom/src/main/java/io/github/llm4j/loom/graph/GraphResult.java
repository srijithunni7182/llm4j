package io.github.llm4j.loom.graph;

import java.util.List;
import java.util.Map;

/** Everything the graph command reports about a script and its imports. */
public record GraphResult(
        int version,
        String entry,
        List<ImportFile> files,
        List<WorkflowGraph> workflows,
        List<AgentInfo> agents,
        Map<String, Object> runBudget,
        List<Diagnostic> diagnostics) {

    /** Version of the JSON this result is written as. */
    public static final int VERSION = 1;

    public GraphResult {
        files = List.copyOf(files);
        workflows = List.copyOf(workflows);
        agents = List.copyOf(agents);
        runBudget = runBudget == null ? Map.of() : runBudget;
        diagnostics = List.copyOf(diagnostics);
    }

    /** True when the entry file was read, so a graph (possibly of no workflows) exists. */
    public boolean hasGraph() {
        return files.stream().anyMatch(f -> f.path().equals(entry));
    }
}
