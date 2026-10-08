package io.github.llm4j.loom.graph;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.prompt.PromptSettings;
import io.github.llm4j.loom.prompt.PromptSupport;
import io.github.llm4j.loom.prompt.PromptUse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds the prompt each agent runs to the graph: a {@code prompt} chip on its steps and a {@code prompt} entry in its details. The prompt files are
 * those of the entry script (the folder it names, the command line, or {@code prompts/} beside it); the agents are looked up across the import closure.
 * Nothing changes when no agent names a prompt, so the output of every other script is exactly what it was.
 */
final class PromptAnnotator {

    record Annotated(List<WorkflowGraph> workflows, List<AgentInfo> agents) {
    }

    static Annotated annotate(LoomScript entry, Path entryFile, PromptSettings settings, List<WorkflowGraph> graphs, List<AgentInfo> agents,
            Map<String, AgentCatalog.Located> definitions) {
        var catalog = PromptSupport.catalog(entry, entryFile, settings);
        Map<String, PromptInfo> byAgent = new LinkedHashMap<>();
        if (catalog != null) {
            // the entry script's agents and, through the closure, everyone else's: the loader merges them, so ask with a merged view
            for (PromptUse use : catalog.usesOf(view(definitions))) {
                byAgent.put(use.agent(), new PromptInfo(use.reference(), use.version(), use.file() == null ? null : use.file().toString()));
            }
        }
        if (byAgent.isEmpty()) {
            return new Annotated(graphs, agents);
        }
        List<WorkflowGraph> outGraphs = new ArrayList<>();
        for (WorkflowGraph graph : graphs) {
            List<GraphNode> nodes = new ArrayList<>();
            for (GraphNode node : graph.nodes()) {
                PromptInfo info = node.agent() == null ? null : byAgent.get(node.agent());
                nodes.add(info == null ? node : node.withAttr("prompt", info.label()));
            }
            outGraphs.add(graph.withNodes(nodes));
        }
        List<AgentInfo> outAgents = new ArrayList<>();
        for (AgentInfo agent : agents) {
            PromptInfo info = byAgent.get(agent.name());
            outAgents.add(info == null ? agent : agent.withPrompt(info));
        }
        return new Annotated(outGraphs, outAgents);
    }

    /** A script holding the agent definitions of the whole closure (first in run order wins), so the catalog can resolve their references. */
    private static LoomScript view(Map<String, AgentCatalog.Located> definitions) {
        LoomScript view = new LoomScript();
        definitions.values().forEach(located -> view.addAgent(located.agent()));
        return view;
    }

    private PromptAnnotator() {
    }
}
