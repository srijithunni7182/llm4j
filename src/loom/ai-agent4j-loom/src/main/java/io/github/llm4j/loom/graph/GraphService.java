package io.github.llm4j.loom.graph;

import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.DecisionDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.WorkflowDef;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the graphs of a script and everything it imports: loads the import closure, draws each workflow, links
 * {@code call} nodes to their callees and collects the settings of the agents the graphs name. Nothing is run, and
 * no model, secret or network is touched.
 */
public final class GraphService {

    private final ImportClosureLoader loader = new ImportClosureLoader();
    private final CallResolver resolver = new CallResolver();

    public GraphResult graph(Path entryFile) {
        return graph(entryFile, io.github.llm4j.loom.prompt.PromptSettings.NONE);
    }

    /** As {@link #graph(Path)}, with the prompt folder and pins a command line gave. */
    public GraphResult graph(Path entryFile, io.github.llm4j.loom.prompt.PromptSettings prompts) {
        ImportClosure closure = loader.load(entryFile);
        List<Diagnostic> diagnostics = new ArrayList<>(closure.diagnostics());
        if (!closure.hasEntry()) {
            return new GraphResult(GraphResult.VERSION, closure.entry().toString(), List.of(), List.of(), List.of(), Map.of(), diagnostics);
        }
        List<Path> runOrder = new ArrayList<>(closure.scripts().keySet());

        GraphBuilder builder = new GraphBuilder(decisionLevels(closure, runOrder));
        List<WorkflowGraph> graphs = new ArrayList<>();
        Map<String, AgentCatalog.Located> agents = new LinkedHashMap<>();
        for (Path file : runOrder) {
            LoomScript script = closure.scripts().get(file);
            for (WorkflowDef workflow : script.getWorkflows()) {
                graphs.add(builder.build(workflow, file.toString()));
            }
            for (AgentDef agent : script.getAgents()) {
                agents.putIfAbsent(agent.getName(), new AgentCatalog.Located(agent, file.toString()));
            }
        }

        CallResolver.Resolution resolution = resolver.resolve(graphs);
        diagnostics.addAll(resolution.diagnostics());
        AgentCatalog.Found found = AgentCatalog.describe(resolution.workflows(), agents);
        diagnostics.addAll(found.diagnostics());
        PromptAnnotator.Annotated annotated = PromptAnnotator.annotate(
                closure.scripts().get(closure.entry()), closure.entry(), prompts, resolution.workflows(), found.agents(), agents);

        List<String> discovery = closure.discovery().stream().map(Path::toString).toList();
        List<WorkflowGraph> ordered = new ArrayList<>(annotated.workflows());
        ordered.sort(Comparator.comparingInt(g -> discovery.indexOf(g.file())));

        List<ImportFile> files = new ArrayList<>();
        for (Path file : closure.discovery()) {
            files.add(new ImportFile(
                    file.toString(), closure.imports().get(file).stream().map(Path::toString).toList()));
        }
        return new GraphResult(
                GraphResult.VERSION,
                closure.entry().toString(),
                files,
                ordered,
                annotated.agents(),
                runBudget(closure, runOrder),
                diagnostics);
    }

    private static Map<String, String> decisionLevels(ImportClosure closure, List<Path> runOrder) {
        Map<String, String> levels = new LinkedHashMap<>();
        for (Path file : runOrder) {
            for (DecisionDef decision : closure.scripts().get(file).getDecisions()) {
                if (decision.getStartAt() != null) {
                    levels.putIfAbsent(decision.getName(), decision.getStartAt().word());
                }
            }
        }
        return levels;
    }

    /** The run budget, from the first file in run order that declares one, as the harness merges them. */
    private static Map<String, Object> runBudget(ImportClosure closure, List<Path> runOrder) {
        for (Path file : runOrder) {
            var budget = closure.scripts().get(file).getBudget();
            if (budget != null) {
                return BudgetSummary.of(budget);
            }
        }
        return Map.of();
    }
}
