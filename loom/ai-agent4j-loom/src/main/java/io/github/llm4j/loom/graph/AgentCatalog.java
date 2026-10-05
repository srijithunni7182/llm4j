package io.github.llm4j.loom.graph;

import io.github.llm4j.loom.ast.AgentDef;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The settings of every agent the graphs name, read from the agent definitions in the import closure. */
final class AgentCatalog {

    /** Agents found, and a warning for each named agent that is defined nowhere. */
    record Found(List<AgentInfo> agents, List<Diagnostic> diagnostics) {
    }

    /** @param definitions agent name to its definition and the file it is in; the first in run order wins */
    static Found describe(List<WorkflowGraph> graphs, Map<String, Located> definitions) {
        Set<String> named = new LinkedHashSet<>();
        for (WorkflowGraph graph : graphs) {
            for (GraphNode node : graph.nodes()) {
                if (node.agent() != null) {
                    named.add(node.agent());
                }
                Object agents = node.attrs().get("agents");
                if (agents instanceof List<?> list) {
                    list.forEach(a -> named.add(String.valueOf(a)));
                }
            }
        }
        List<AgentInfo> found = new ArrayList<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (String name : named) {
            if (name.contains("{")) {
                continue; // a target chosen at run time
            }
            Located located = definitions.get(name);
            if (located == null) {
                SourceRef use = firstUse(graphs, name);
                diagnostics.add(Diagnostic.warning(
                        use.file(), use.line(), "Agent " + name + " is not defined in this file or its imports."));
            } else {
                found.add(info(located));
            }
        }
        return new Found(found, diagnostics);
    }

    private static SourceRef firstUse(List<WorkflowGraph> graphs, String agent) {
        for (WorkflowGraph graph : graphs) {
            for (GraphNode node : graph.nodes()) {
                boolean uses = agent.equals(node.agent())
                        || (node.attrs().get("agents") instanceof List<?> l && l.contains(agent));
                if (uses) {
                    return node.source() != null ? node.source() : new SourceRef(graph.file(), graph.line());
                }
            }
        }
        return new SourceRef("", 0);
    }

    private static AgentInfo info(Located located) {
        AgentDef a = located.agent();
        return new AgentInfo(
                a.getName(),
                a.getModel(),
                a.getTemperature(),
                a.getPersona(),
                a.getTools(),
                a.getMcpServers(),
                a.getSkills(),
                a.getKnowledgeBases(),
                a.getApprove(),
                a.isApproveAll(),
                BudgetSummary.of(a.getBudget()),
                a.getMaxIterations(),
                a.getLine() > 0 ? new SourceRef(located.file(), a.getLine()) : null);
    }

    /** An agent definition and the file it is written in. */
    record Located(AgentDef agent, String file) {
    }

    private AgentCatalog() {
    }
}
