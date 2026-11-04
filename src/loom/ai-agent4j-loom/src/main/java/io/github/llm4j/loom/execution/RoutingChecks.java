package io.github.llm4j.loom.execution;

import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.SchemaDef;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.ToolDef;
import io.github.llm4j.loom.ast.WorkflowDef;
import io.github.llm4j.loom.security.Capabilities;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The static checks of a delegate whose target a model chooses (docs/specs/27-boundary-approvals,
 * R11): every name in an allow-list is a declared agent; a target with no allow-list and no enum
 * behind it could point at any agent, so it is an error when some agent holds a tool that changes
 * things or reaches private data, and a warning otherwise.
 */
final class RoutingChecks {

    private RoutingChecks() {}

    static void run(ScriptValidator.Checker c) {
        Set<String> agents = new HashSet<>();
        c.script().getAgents().forEach(a -> agents.add(a.getName()));
        Set<String> enumFields = new HashSet<>();
        c.script().getAgents().forEach(a -> collectEnums(a.getOutputSchema(), enumFields));
        c.script().getExtractors().forEach(x -> collectEnums(x.getFields(), enumFields));
        for (WorkflowDef w : c.script().getWorkflows()) {
            StatementWalker.walk(
                    w.getStatements(),
                    s -> {
                        if (s instanceof DelegateStmt d) {
                            collectEnums(d.getExpecting(), enumFields);
                        }
                    });
        }
        Map<String, ToolDef> tools = new HashMap<>();
        c.script().getTools().forEach(t -> tools.put(t.getName(), t));
        for (WorkflowDef w : c.script().getWorkflows()) {
            StatementWalker.walk(
                    w.getStatements(),
                    s -> {
                        if (s instanceof DelegateStmt d && d.hasDynamicTarget())
                            check(c, d, agents, enumFields, tools);
                    });
        }
    }

    private static void check(
            ScriptValidator.Checker c,
            DelegateStmt d,
            Set<String> agents,
            Set<String> enumFields,
            Map<String, ToolDef> tools) {
        String who = "delegate " + d.getTargetAgent();
        for (String name : d.getAllowedTargets()) {
            if (!agents.contains(name))
                c.error(d.getLine(), who, "in [..] names " + name + ", which is not an agent");
        }
        if (!d.getAllowedTargets().isEmpty()) return;
        String path = d.getTargetAgent().substring(1, d.getTargetAgent().length() - 1);
        String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
        if (enumFields.contains(field)) return; // its values are a fixed list the answer must match
        List<String> risky = c.script().getAgents().stream().filter(a -> reachesMuch(a, tools)).map(AgentDef::getName).toList();
        String how =
                " Bound it: write the agents after it, delegate ... to "
                        + d.getTargetAgent()
                        + " in [Researcher, Writer], or make the field an enum in the schema of the agent or extractor that writes it.";
        if (!risky.isEmpty()) {
            c.error(
                    d.getLine(),
                    who,
                    "a model chooses which agent runs here, and any agent could be chosen, including "
                            + String.join(", ", risky)
                            + ", which can change things or reach private data."
                            + how);
        } else {
            c.warn(
                    d.getLine(),
                    who,
                    "a model chooses which agent runs here, with no list of agents it may choose."
                            + how);
        }
    }

    private static boolean reachesMuch(AgentDef a, Map<String, ToolDef> tools) {
        if (!a.getKnowledgeBases().isEmpty() || !a.getMcpServers().isEmpty()) return true;
        for (String t : a.getTools()) {
            Capabilities caps = Capabilities.resolve(t, tools, Map.of());
            if (caps.level() == TaskEffect.CHANGES || caps.privateData()) return true;
        }
        return false;
    }

    private static void collectEnums(SchemaDef schema, Set<String> into) {
        if (schema == null) return;
        if (schema.getFields() != null)
            schema.getFields().forEach(
                    (name, field) -> {
                        if (field.getType() == SchemaDef.Type.ENUM) into.add(name);
                        collectEnums(field, into);
                    });
        collectEnums(schema.getElementType(), into);
    }
}
