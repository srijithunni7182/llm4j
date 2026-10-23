package io.github.llm4j.loom.review.fact;

import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.AltStmt;
import io.github.llm4j.loom.ast.BroadcastStmt;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.LoopStmt;
import io.github.llm4j.loom.ast.ParallelStmt;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.WorkflowDef;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Facts that need the statements themselves rather than the graph: whether a branch condition reads
 * a field of an answer that has no declared shape, and whether the branches of a parallel round
 * bind the same variable. They fill fields of the {@code step} entities, so they run after the
 * steps exist, and they match a step to its statement by line and kind.
 */
final class ConditionFacts implements FactProvider {

    private static final Pattern FIELD =
            Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)");

    @Override
    public String name() {
        return "condition";
    }

    @Override
    public void provide(FactInput input, FactBase.Builder out) {
        List<Entity> steps = out.entitiesOf("step");
        if (steps.isEmpty()) return;
        Map<String, Statement> byKey = new HashMap<>();
        Map<String, Boolean> typed =
                new HashMap<>(); // variable -> every binding agent has a schema
        Map<String, AgentDef> agents = new HashMap<>();
        input.review().script().getAgents().forEach(a -> agents.put(a.getName(), a));
        for (WorkflowDef w : input.review().script().getWorkflows())
            StatementWalker.walk(
                    w.getStatements(),
                    s -> {
                        byKey.putIfAbsent(
                                w.getName()
                                        + ":"
                                        + s.getLine()
                                        + ":"
                                        + s.getClass().getSimpleName(),
                                s);
                        if (s instanceof DelegateStmt d && d.getVariableName() != null) {
                            AgentDef a =
                                    d.hasDynamicTarget() ? null : agents.get(d.getTargetAgent());
                            boolean schema =
                                    a == null
                                            || a.getOutputSchema() != null
                                            || d.getExpecting() != null;
                            typed.merge(d.getVariableName(), schema, Boolean::logicalAnd);
                        }
                    });
        for (Entity step : steps) {
            Statement s = statement(byKey, step);
            out.set(step, "readsFreeText", s != null && readsFreeText(s, typed));
            out.set(
                    step,
                    "writesSameVariableAsSibling",
                    s instanceof ParallelStmt p && duplicates(p));
        }
    }

    private static Statement statement(Map<String, Statement> byKey, Entity step) {
        String kind = (String) step.get("kind");
        Object wf = step.get("workflow");
        if (!(wf instanceof Entity w)) return null;
        String cls =
                switch (kind) {
                    case "alt" -> "AltStmt";
                    case "loop" -> "LoopStmt";
                    case "parallel" -> "ParallelStmt";
                    default -> null;
                };
        if (cls == null) return null;
        return byKey.get(w.get("name") + ":" + step.get("line") + ":" + cls);
    }

    private static boolean readsFreeText(Statement s, Map<String, Boolean> typed) {
        String condition =
                s instanceof AltStmt a
                        ? a.getCondition()
                        : s instanceof LoopStmt l ? l.getCondition() : null;
        if (condition == null) return false;
        Matcher m = FIELD.matcher(condition);
        while (m.find()) {
            Boolean schema = typed.get(m.group(1));
            if (schema != null && !schema) return true;
        }
        return false;
    }

    private static boolean duplicates(ParallelStmt p) {
        Set<String> seen = new HashSet<>();
        List<String> names = new ArrayList<>();
        for (Statement s : p.getBody()) {
            if (s instanceof DelegateStmt d && d.getVariableName() != null)
                names.add(d.getVariableName());
            else if (s instanceof BroadcastStmt b && b.getVariableName() != null)
                names.add(b.getVariableName());
        }
        for (String n : names) if (!seen.add(n)) return true;
        return false;
    }
}
