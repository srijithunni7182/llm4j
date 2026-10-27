package io.github.llm4j.loom.review.fact;

import io.github.llm4j.loom.ast.AltStmt;
import io.github.llm4j.loom.ast.BroadcastStmt;
import io.github.llm4j.loom.ast.DecisionDef;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.LoopStmt;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.WorkflowDef;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What an agent's answer is used for: whether a decision or ladder names the agent, and whether a
 * variable its answer is bound to is read by a condition that picks a branch. It reads the
 * statements, not the graph, so it works without one.
 */
final class AgentUseFacts implements FactProvider {

    private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    @Override
    public String name() {
        return "agentUse";
    }

    @Override
    public void provide(FactInput input, FactBase.Builder out) {
        Set<String> decided = new HashSet<>();
        for (DecisionDef d : input.review().script().getDecisions())
            if (d.getAgent() != null) decided.add(d.getAgent());
        Set<String> branchVars = new HashSet<>();
        Map<String, Set<String>> agentsOfVar = new HashMap<>();
        for (WorkflowDef w : input.review().script().getWorkflows())
            StatementWalker.walk(
                    w.getStatements(),
                    s -> {
                        String cond =
                                s instanceof AltStmt a
                                        ? a.getCondition()
                                        : s instanceof LoopStmt l ? l.getCondition() : null;
                        if (cond != null) {
                            Matcher m = WORD.matcher(cond);
                            while (m.find()) branchVars.add(m.group());
                        }
                        if (s instanceof DelegateStmt d
                                && d.getVariableName() != null
                                && d.getTargetAgent() != null)
                            agentsOfVar
                                    .computeIfAbsent(d.getVariableName(), k -> new HashSet<>())
                                    .add(d.getTargetAgent());
                        if (s instanceof BroadcastStmt b && b.getVariableName() != null)
                            agentsOfVar
                                    .computeIfAbsent(b.getVariableName(), k -> new HashSet<>())
                                    .addAll(b.getTargetAgents());
                    });
        Set<String> feedsBranch = new HashSet<>();
        for (String v : branchVars) feedsBranch.addAll(agentsOfVar.getOrDefault(v, Set.of()));
        for (Entity agent : out.entitiesOf("agent")) {
            String name = (String) agent.get("name");
            out.set(agent, "feedsDecision", decided.contains(name));
            out.set(agent, "feedsBranch", feedsBranch.contains(name));
        }
    }
}
