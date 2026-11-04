package io.github.llm4j.loom.review.fact;

import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.AltStmt;
import io.github.llm4j.loom.ast.CheckStmt;
import io.github.llm4j.loom.ast.DecideStmt;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.ExtractStmt;
import io.github.llm4j.loom.ast.IntentClassifyStmt;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.RunStmt;
import io.github.llm4j.loom.ast.SetStmt;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.UseStmt;
import io.github.llm4j.loom.ast.ValueExpr;
import io.github.llm4j.loom.ast.WorkflowDef;
import io.github.llm4j.loom.security.Capabilities;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether a step that changes something acts on text an agent wrote freely, with nothing validating
 * it on the way (docs/specs/27-boundary-approvals, R10, rule LR11). The facts are structural: which
 * variable an agent's free prose is bound to, which steps read it, and whether a step that checks
 * it (an {@code extract}, an {@code intentclassify}, a {@code check}, a {@code run}, a {@code
 * decide}, or an {@code approve} on the acting step) stands between. What a prompt means is never
 * read.
 */
final class HandoffFacts implements FactProvider {

    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)(?:\\.[A-Za-z0-9_.]+)?}");

    @Override
    public String name() {
        return "handoff";
    }

    @Override
    public void provide(FactInput input, FactBase.Builder out) {
        LoomScript script = input.review().script();
        for (Entity step : out.entitiesOf("step")) {
            out.set(step, "actsOnUnvalidatedProse", false);
            out.set(step, "proseVariable", "");
            out.set(step, "proseAgent", "");
        }
        Map<String, AgentDef> agents = new HashMap<>();
        script.getAgents().forEach(a -> agents.put(a.getName(), a));
        Map<String, io.github.llm4j.loom.ast.ToolDef> tools = new HashMap<>();
        script.getTools().forEach(t -> tools.put(t.getName(), t));
        for (WorkflowDef w : script.getWorkflows()) {
            // variable → the agent whose free prose it holds, until something validates it
            Map<String, String> prose = new LinkedHashMap<>();
            List<Statement> all = new ArrayList<>();
            StatementWalker.walk(w.getStatements(), all::add);
            for (Statement s : all) {
                if (s instanceof DelegateStmt d) {
                    String used = usedProse(d.getPayload(), prose);
                    AgentDef target = d.hasDynamicTarget() ? null : agents.get(d.getTargetAgent());
                    if (used != null && target != null && holdsChangingTool(target, tools)) {
                        mark(out, "delegate", d.getLine(), used, prose.get(used));
                    }
                    if (d.getVariableName() != null) {
                        AgentDef a = d.hasDynamicTarget() ? null : agents.get(d.getTargetAgent());
                        boolean free = a != null && a.getOutputSchema() == null && d.getExpecting() == null;
                        if (free) prose.put(d.getVariableName(), d.getTargetAgent());
                        else prose.remove(d.getVariableName());
                    }
                } else if (s instanceof UseStmt u) {
                    String used = null;
                    for (RunStmt.Arg a : u.getArgs()) used = first(used, argProse(a, prose));
                    boolean changes =
                            Capabilities.resolve(u.getTool(), tools, Map.of()).level()
                                    == io.github.llm4j.agent.task.TaskEffect.CHANGES;
                    if (used != null && changes && !u.isApprove())
                        mark(out, "use", u.getLine(), used, prose.get(used));
                } else if (s instanceof SetStmt set) {
                    String used = valueProse(set.getValue(), prose);
                    if (used != null) prose.put(set.getName(), prose.get(used));
                    else prose.remove(set.getName());
                } else if (s instanceof ExtractStmt x) {
                    for (RunStmt.Arg a : x.getInputs()) validate(a, prose);
                } else if (s instanceof IntentClassifyStmt c) {
                    for (RunStmt.Arg a : c.getInputs()) validate(a, prose);
                } else if (s instanceof RunStmt r && !r.isApprove()) {
                    // a task reads it in code: that is a check of it
                    for (RunStmt.Arg a : r.getArgs()) validate(a, prose);
                } else if (s instanceof CheckStmt k) {
                    prose.remove(k.getSubject());
                } else if (s instanceof DecideStmt || s instanceof AltStmt) {
                    // a decision or a branch on it is a person's or the script's judgement
                    if (s instanceof DecideStmt) prose.clear();
                }
            }
        }
    }

    private static void mark(FactBase.Builder out, String kind, int line, String variable, String agent) {
        for (Entity step : out.entitiesOf("step")) {
            if (!kind.equals(String.valueOf(step.get("kind")))) continue;
            if (!(step.get("line") instanceof Long l) || l != line) continue;
            out.set(step, "actsOnUnvalidatedProse", true);
            out.set(step, "proseVariable", variable);
            out.set(step, "proseAgent", agent == null ? "" : agent);
        }
    }

    private static boolean holdsChangingTool(AgentDef agent, Map<String, io.github.llm4j.loom.ast.ToolDef> tools) {
        for (String t : agent.getTools())
            if (Capabilities.resolve(t, tools, Map.of()).level() == io.github.llm4j.agent.task.TaskEffect.CHANGES
                    && !agent.isApproveAll()
                    && !agent.getApprove().contains(t)) return true;
        return false;
    }

    private static String usedProse(String text, Map<String, String> prose) {
        if (text == null) return null;
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) if (prose.containsKey(m.group(1))) return m.group(1);
        return null;
    }

    private static String argProse(RunStmt.Arg a, Map<String, String> prose) {
        return switch (a.kind()) {
            case STRING -> usedProse(a.text(), prose);
            case REFERENCE -> {
                String head = a.text().contains(".") ? a.text().substring(0, a.text().indexOf('.')) : a.text();
                yield prose.containsKey(head) ? head : null;
            }
            default -> null;
        };
    }

    private static String valueProse(ValueExpr v, Map<String, String> prose) {
        if (v instanceof ValueExpr.Text t) return usedProse(t.text(), prose);
        if (v instanceof ValueExpr.Ref r) {
            String head = r.path().contains(".") ? r.path().substring(0, r.path().indexOf('.')) : r.path();
            return prose.containsKey(head) ? head : null;
        }
        if (v instanceof ValueExpr.Obj o) {
            for (ValueExpr f : o.fields().values()) {
                String u = valueProse(f, prose);
                if (u != null) return u;
            }
        }
        return null;
    }

    private static void validate(RunStmt.Arg a, Map<String, String> prose) {
        String used = argProse(a, prose);
        if (used != null) prose.remove(used);
    }

    private static String first(String a, String b) {
        return a != null ? a : b;
    }

    private static final Set<String> UNUSED = new HashSet<>();
}
