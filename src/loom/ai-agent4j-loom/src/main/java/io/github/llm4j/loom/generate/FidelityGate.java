package io.github.llm4j.loom.generate;

import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.HumanPromptStmt;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.ToolDef;
import io.github.llm4j.loom.ast.WorkflowDef;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.security.Capabilities;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * G5: the script is the plan and nothing else. Every agent, classifier, tool, effect and person
 * asked in the plan is in the script, and nothing in the script is missing from the plan, so a tool
 * or an effect nobody listed cannot get in. Effects the plan says need approval have it in the
 * script, and the script has a budget.
 */
public final class FidelityGate implements Gate {

    @Override
    public String id() {
        return "G5";
    }

    @Override
    public GateResult run(Workspace ws, Plan plan) {
        LoomScript script;
        try {
            script = new LoomLoader().load(ws.script().toString());
        } catch (Exception e) {
            return new GateResult(
                    "G5",
                    "plan",
                    List.of(
                            new GateResult.Problem(
                                    "main.loom",
                                    0,
                                    "",
                                    "The script cannot be read, so it cannot be compared with the plan: "
                                            + e.getMessage())));
        }
        return new GateResult("G5", "plan", compare(plan, script));
    }

    /** The differences between a plan and a script, in both directions. */
    public static List<GateResult.Problem> compare(Plan plan, LoomScript script) {
        List<GateResult.Problem> out = new ArrayList<>();
        Set<String> planAgents = new TreeSet<>();
        plan.agents().forEach(a -> planAgents.add(a.name()));
        Set<String> scriptAgents = new TreeSet<>();
        script.getAgents().forEach(a -> scriptAgents.add(a.getName()));
        diff(out, "agent", planAgents, scriptAgents);

        Set<String> planClassifiers = new TreeSet<>();
        plan.classifiers().forEach(c -> planClassifiers.add(c.name()));
        Set<String> scriptClassifiers = new TreeSet<>();
        script.getIntentClassifiers().forEach(c -> scriptClassifiers.add(c.getName()));
        diff(out, "classifier", planClassifiers, scriptClassifiers);

        Map<String, ToolDef> scriptTools = new LinkedHashMap<>();
        script.getTools().forEach(t -> scriptTools.put(t.getName(), t));
        Set<String> usedTools = new TreeSet<>(scriptTools.keySet());
        for (AgentDef a : script.getAgents()) usedTools.addAll(a.getTools());
        Set<String> planTools = new TreeSet<>();
        plan.tools().forEach(t -> planTools.add(t.name()));
        diff(out, "tool", planTools, usedTools);

        // an effect the script can cause must be one the plan lists
        Set<String> planEffects = new TreeSet<>();
        plan.effects().forEach(e -> planEffects.add(e.tool()));
        for (String tool : usedTools) {
            Capabilities c = Capabilities.resolve(tool, scriptTools, Map.of());
            boolean declaredStub =
                    plan.tools().stream().anyMatch(t -> t.name().equals(tool) && t.stub());
            if (c.effect() && !planEffects.contains(tool) && !declaredStub)
                out.add(
                        new GateResult.Problem(
                                "main.loom",
                                0,
                                "plan",
                                "The tool "
                                        + tool
                                        + " can change something ("
                                        + c.note()
                                        + ") but the plan lists no effect for it."));
        }
        for (Plan.Effect e : plan.effects())
            if ("required".equals(e.approval())) {
                for (AgentDef a : script.getAgents()) {
                    if (!a.getTools().contains(e.tool())) continue;
                    if (!a.isApproveAll() && !a.getApprove().contains(e.tool()))
                        out.add(
                                new GateResult.Problem(
                                        "main.loom",
                                        0,
                                        "plan",
                                        "The plan has a person approve "
                                                + e.tool()
                                                + ", but agent "
                                                + a.getName()
                                                + " can call it without approve:."));
                }
            }

        boolean planHumans = !plan.humans().isEmpty();
        boolean scriptHumans = false;
        for (WorkflowDef w : script.getWorkflows()) {
            boolean[] found = {false};
            StatementWalker.walk(
                    w.getStatements(),
                    st -> {
                        if (st instanceof HumanPromptStmt) found[0] = true;
                    });
            scriptHumans |= found[0];
        }
        if (planHumans && !scriptHumans)
            out.add(
                    new GateResult.Problem(
                            "main.loom",
                            0,
                            "plan",
                            "The plan asks a person ("
                                    + plan.humans().get(0).name()
                                    + ") but the script never does."));
        if (!planHumans && scriptHumans)
            out.add(
                    new GateResult.Problem(
                            "main.loom",
                            0,
                            "plan",
                            "The script asks a person, but the plan lists nobody to ask."));
        if (script.getBudget() == null)
            out.add(
                    new GateResult.Problem(
                            "main.loom",
                            0,
                            "plan",
                            "The script has no budget { } block, and every generated workflow has one."));
        return out;
    }

    private static void diff(
            List<GateResult.Problem> out, String what, Set<String> planned, Set<String> actual) {
        for (String n : planned)
            if (!actual.contains(n))
                out.add(
                        new GateResult.Problem(
                                "main.loom",
                                0,
                                "plan",
                                "The plan has the "
                                        + what
                                        + " "
                                        + n
                                        + ", which the script does not."));
        for (String n : actual)
            if (!planned.contains(n))
                out.add(
                        new GateResult.Problem(
                                "main.loom",
                                0,
                                "plan",
                                "The script has the "
                                        + what
                                        + " "
                                        + n
                                        + ", which the plan does not list."));
    }
}
