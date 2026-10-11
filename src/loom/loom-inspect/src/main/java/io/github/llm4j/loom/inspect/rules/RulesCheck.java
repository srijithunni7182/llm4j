package io.github.llm4j.loom.inspect.rules;

import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.BroadcastStmt;
import io.github.llm4j.loom.ast.CallStmt;
import io.github.llm4j.loom.ast.DecideStmt;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.ForEachStmt;
import io.github.llm4j.loom.ast.HumanPromptStmt;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.RunStmt;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.WorkflowDef;
import io.github.llm4j.loom.autonomy.expr.Suggest;
import io.github.llm4j.loom.inspect.state.InspectRules;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Checks a rules file against the script it will watch: every named agent must exist, and a {@code no_progress} variable must be one the
 * workflow stores or takes. The script itself is never touched.
 */
public final class RulesCheck {

    private RulesCheck() { }

    public static List<RulesFile.Problem> check(LoomScript script, RulesFile.Parsed parsed) {
        List<RulesFile.Problem> out = new ArrayList<>(parsed.problems());
        Set<String> agents = new TreeSet<>();
        for (AgentDef a : script.getAgents()) agents.add(a.getName());
        Set<String> variables = variablesOf(script);
        for (Map.Entry<String, InspectRules> e : parsed.rules().entrySet()) {
            int line = parsed.lines().getOrDefault(e.getKey(), 0);
            if (!RulesFile.ALL.equals(e.getKey()) && !agents.contains(e.getKey())) {
                out.add(new RulesFile.Problem(line, true, "no agent named " + e.getKey() + " in the script" + Suggest.hint(e.getKey(), agents)));
            }
            String variable = e.getValue().noProgressVariable();
            if (variable != null) {
                String root = variable.split("\\.")[0];
                if (!variables.contains(root)) {
                    out.add(new RulesFile.Problem(line, true, "no_progress: no workflow variable named " + root
                            + " (a variable is what a step stores with ->, or a workflow parameter)" + Suggest.hint(root, variables)));
                }
            }
        }
        return out;
    }

    /** Every name a workflow stores a result under, and its parameters: what a rule may call a variable. */
    static Set<String> variablesOf(LoomScript script) {
        Set<String> names = new TreeSet<>();
        for (WorkflowDef w : script.getWorkflows()) {
            names.addAll(w.getParameters());
            StatementWalker.walk(w.getStatements(), s -> {
                String n = null;
                if (s instanceof DelegateStmt d) n = d.getVariableName();
                else if (s instanceof HumanPromptStmt h) n = h.getVariableName();
                else if (s instanceof BroadcastStmt b) n = b.getVariableName();
                else if (s instanceof CallStmt c) n = c.getResultVariable();
                else if (s instanceof DecideStmt d) n = d.getVariable();
                else if (s instanceof RunStmt r) n = r.getVariableName();
                else if (s instanceof ForEachStmt f) n = f.getItemName();
                if (n != null && !n.isBlank() && !n.startsWith("{")) names.add(n);
            });
        }
        return names;
    }
}
