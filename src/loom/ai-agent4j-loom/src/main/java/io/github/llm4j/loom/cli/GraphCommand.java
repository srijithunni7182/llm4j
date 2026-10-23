package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.graph.Diagnostic;
import io.github.llm4j.loom.graph.GraphJson;
import io.github.llm4j.loom.graph.GraphResult;
import io.github.llm4j.loom.graph.GraphService;
import io.github.llm4j.loom.graph.MermaidRenderer;
import io.github.llm4j.loom.graph.WorkflowGraph;
import java.io.File;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code weave graph}: the workflows of a script and its imports as nodes and edges. Nothing is
 * run: no model, no secret, no tool and no network is touched, so it needs no keys and no {@code
 * .loot} file.
 *
 * <p>Standard output carries only the result (JSON, or Mermaid text). Problems that stop a graph
 * from being built are written to standard error. Problems that leave a partial graph are inside
 * the JSON.
 */
@Command(
        name = "graph",
        description =
                "Draws the workflows of a script and its imports as a graph, as JSON (default) or Mermaid, without running anything.")
final class GraphCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "The .loom script.")
    File script;

    @Option(names = "--format", description = "json (default) or mermaid.", defaultValue = "json")
    String format;

    @Option(names = "--workflow", description = "Only this workflow.")
    String workflow;

    @Option(
            names = "--run",
            description =
                    "A run directory to draw on the graph: how often each statement ran, the edges taken, what was chosen, and (with several runs) the statements none reached (repeatable).")
    List<File> runs = new java.util.ArrayList<>();

    @picocli.CommandLine.Mixin PromptOptions promptOptions = new PromptOptions();

    @Override
    public Integer call() {
        WeaveEnv env = promptOptions.apply(WeaveEnv.system());
        return env == null ? 2 : graph(this, env);
    }

    static int graph(GraphCommand c, WeaveEnv env) {
        if (!c.format.equals("json") && !c.format.equals("mermaid")) {
            env.err().println("Error: --format takes json or mermaid.");
            return 2;
        }
        GraphResult result = new GraphService().graph(c.script.toPath(), env.prompts());
        if (!result.hasGraph()) {
            for (Diagnostic d : result.diagnostics()) {
                env.err().println("Error: " + where(d) + d.message());
            }
            return 2;
        }
        if (c.workflow != null) {
            List<WorkflowGraph> chosen =
                    result.workflows().stream().filter(w -> w.name().equals(c.workflow)).toList();
            if (chosen.isEmpty()) {
                String known =
                        result.workflows().stream()
                                .map(WorkflowGraph::name)
                                .collect(Collectors.joining(", "));
                env.err()
                        .println(
                                "Error: no workflow named "
                                        + c.workflow
                                        + ". Known workflows: "
                                        + (known.isEmpty() ? "none" : known)
                                        + ".");
                return 2;
            }
            result =
                    new GraphResult(
                            result.version(),
                            result.entry(),
                            result.files(),
                            chosen,
                            result.agents(),
                            result.runBudget(),
                            result.diagnostics());
        }
        io.github.llm4j.loom.overlay.RunOverlay.Overlay overlay = null;
        if (!c.runs.isEmpty()) {
            List<io.github.llm4j.loom.overlay.RunOverlay.Run> read = new java.util.ArrayList<>();
            for (File dir : c.runs) {
                try {
                    read.add(OverlayRuns.read(dir.toPath(), c.script.toPath()));
                } catch (Exception e) {
                    env.err()
                            .println(
                                    "Error: "
                                            + dir
                                            + ": "
                                            + (e.getMessage() == null ? e : e.getMessage()));
                    return 2;
                }
            }
            overlay = io.github.llm4j.loom.overlay.RunOverlay.build(result, read);
        }
        if (c.format.equals("json")) {
            env.out()
                    .println(
                            overlay == null
                                    ? GraphJson.write(result)
                                    : GraphJson.write(
                                            result,
                                            java.util.Map.of(
                                                    "overlay",
                                                    io.github.llm4j.loom.overlay.OverlayJson.tree(
                                                            overlay))));
        } else {
            env.out().println(mermaid(result, overlay));
            result.diagnostics()
                    .forEach(
                            d ->
                                    env.err()
                                            .println(
                                                    d.severity().word()
                                                            + ": "
                                                            + where(d)
                                                            + d.message()));
        }
        return 0;
    }

    private static String mermaid(
            GraphResult result, io.github.llm4j.loom.overlay.RunOverlay.Overlay overlay) {
        StringBuilder out = new StringBuilder();
        for (WorkflowGraph graph : result.workflows()) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append("%% workflow ")
                    .append(graph.name())
                    .append(" (")
                    .append(graph.file())
                    .append(")\n");
            out.append(
                    overlay == null
                            ? MermaidRenderer.render(graph)
                            : io.github.llm4j.loom.overlay.OverlayMermaid.render(
                                    graph, overlay.workflows().get(graph.name())));
        }
        return out.toString().stripTrailing();
    }

    private static String where(Diagnostic d) {
        return d.file().isEmpty() ? "" : d.file() + (d.line() > 0 ? ":" + d.line() : "") + ": ";
    }
}
