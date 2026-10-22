package io.github.llm4j.loom.review.fact;

import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.DecideStmt;
import io.github.llm4j.loom.ast.HumanPromptStmt;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.SleepStmt;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.review.model.Location;

/** The {@code script} entity: facts about the script as a whole. */
final class ScriptFacts implements FactProvider {

    @Override
    public String name() {
        return "script";
    }

    @Override
    public void provide(FactInput input, FactBase.Builder out) {
        LoomScript script = input.review().script();
        Entity e = out.add("script", "script", Location.at(input.review().file(), 0));
        out.set(e, "file", input.review().file());
        out.set(e, "workflowCount", (long) script.getWorkflows().size());
        out.set(e, "agentCount", (long) script.getAgents().size());
        out.set(e, "hasBudget", script.getBudget() != null);
        out.set(e, "isScheduled", !script.getSchedules().isEmpty());
        out.set(e, "usesPausing", usesPausing(script));
    }

    private static boolean usesPausing(LoomScript script) {
        for (AgentDef a : script.getAgents())
            if (a.isApproveAll() || !a.getApprove().isEmpty()) return true;
        return StatementWalker.any(
                script,
                s ->
                        s instanceof HumanPromptStmt
                                || s instanceof SleepStmt
                                || s instanceof DecideStmt);
    }
}
