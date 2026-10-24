package io.github.llm4j.loom.generate;

import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.LoopStmt;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.ToolDef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The safe defaults of spec 12 R10, read from the script: every loop is bounded, a step that uses
 * tools has a timeout and a retry or a handler, an agent that sees personal data masks it, and a
 * shell tool is there only when the plan lists it with a reason. (A budget, the effects the plan
 * lists and their approval are G5's own.)
 */
public final class SafeDefaults {

    private SafeDefaults() {}

    public static List<GateResult.Problem> check(Plan plan, LoomScript script) {
        List<GateResult.Problem> out = new ArrayList<>();
        Map<String, ToolDef> tools = new LinkedHashMap<>();
        script.getTools().forEach(t -> tools.put(t.getName(), t));
        Map<String, AgentDef> agents = new LinkedHashMap<>();
        script.getAgents().forEach(a -> agents.put(a.getName(), a));

        for (AgentDef a : script.getAgents()) {
            if (plan.personalData()
                    && !a.getTools().isEmpty()
                    && (a.getGuard() == null || a.getGuard().get("pii") == null))
                out.add(
                        problem(
                                "The plan says there is personal data, but agent "
                                        + a.getName()
                                        + " has no guard { pii: mask }."));
        }
        for (ToolDef t : script.getTools()) {
            if ("shell".equals(t.getKind())) {
                boolean reasoned =
                        plan.tools().stream()
                                .anyMatch(
                                        p ->
                                                p.name().equals(t.getName())
                                                        && p.reason() != null
                                                        && !p.reason().isBlank());
                if (!reasoned)
                    out.add(
                            problem(
                                    "A shell tool ("
                                            + t.getName()
                                            + ") needs a reason in the plan; use another tool if you can."));
            }
        }
        script.getWorkflows()
                .forEach(
                        w ->
                                StatementWalker.walk(
                                        w.getStatements(),
                                        st -> {
                                            if (st instanceof LoopStmt l
                                                    && l.getMaxIterations() <= 0)
                                                out.add(
                                                        problem(
                                                                "A loop in workflow "
                                                                        + w.getName()
                                                                        + " has no max N."));
                                            if (st instanceof DelegateStmt d) {
                                                AgentDef a = agents.get(d.getTargetAgent());
                                                if (a == null || a.getTools().isEmpty()) return;
                                                if (d.getTimeoutMillis() <= 0)
                                                    out.add(
                                                            problem(
                                                                    "A step in workflow "
                                                                            + w.getName()
                                                                            + " that uses the tools of "
                                                                            + a.getName()
                                                                            + " has no timeout."));
                                                if (d.getRetryCount() <= 0
                                                        && d.getOnFailure().isEmpty())
                                                    out.add(
                                                            problem(
                                                                    "A step in workflow "
                                                                            + w.getName()
                                                                            + " that uses the tools of "
                                                                            + a.getName()
                                                                            + " has neither retry nor on_failure."));
                                            }
                                        }));
        return out;
    }

    private static GateResult.Problem problem(String message) {
        return new GateResult.Problem("main.loom", 0, "plan", message);
    }
}
