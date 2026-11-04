package io.github.llm4j.loom.review.fact;

import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.CheckStmt;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.EvaluatorDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.LoopStmt;
import io.github.llm4j.loom.ast.ReturnStmt;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.ValueExpr;
import io.github.llm4j.loom.ast.WorkflowDef;
import io.github.llm4j.loom.graph.Kinds;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether the doer grades its own work (docs/specs/28-evaluation-first, R13, M12): a loop that ends
 * when the agent that produced the work says it is done (rule LR12), and a policy check whose
 * evaluator runs on the same model as the agent whose text it judges (rule LR14). The facts are
 * structural: which agent binds which variable, what a loop's condition reads, which model each
 * node uses. What a prompt means is never read.
 */
final class SelfGradingFacts implements FactProvider {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)(?:\\.[A-Za-z0-9_.]+)?}");
    private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    @Override
    public String name() {
        return "selfgrading";
    }

    @Override
    public void provide(FactInput input, FactBase.Builder out) {
        for (Entity step : out.entitiesOf("step")) {
            out.set(step, "decidesItsOwnLoop", false);
            out.set(step, "loopDecider", "");
            out.set(step, "checkedByOwnModel", false);
            out.set(step, "checkedAgent", "");
        }
        LoomScript script = input.review().script();
        Map<String, AgentDef> agents = new HashMap<>();
        script.getAgents().forEach(a -> agents.put(a.getName(), a));
        for (WorkflowDef w : script.getWorkflows()) {
            List<Statement> all = new ArrayList<>();
            StatementWalker.walk(w.getStatements(), all::add);
            Set<String> readAnywhere = placeholders(all);
            Map<String, String> writer = new HashMap<>(); // variable -> agent that bound it
            for (Statement s : all)
                if (s instanceof DelegateStmt d && d.getVariableName() != null && !d.hasDynamicTarget())
                    writer.put(d.getVariableName(), d.getTargetAgent());
            for (Statement s : all) {
                if (s instanceof LoopStmt loop) loop(out, loop, readAnywhere);
                if (s instanceof CheckStmt check) check(out, check, script, agents, writer);
            }
        }
    }

    // ── loops ────────────────────────────────────────────────────────────────────────────────

    private static void loop(FactBase.Builder out, LoopStmt loop, Set<String> readAnywhere) {
        List<Statement> body = new ArrayList<>();
        StatementWalker.walk(loop.getBody(), body::add);
        Map<String, List<String>> bound = new HashMap<>(); // agent -> variables it binds in the body
        Map<String, String> boundBy = new HashMap<>(); // variable -> agent
        for (Statement s : body)
            if (s instanceof DelegateStmt d && d.getVariableName() != null && !d.hasDynamicTarget()) {
                bound.computeIfAbsent(d.getTargetAgent(), k -> new ArrayList<>()).add(d.getVariableName());
                boundBy.put(d.getVariableName(), d.getTargetAgent());
            }
        for (String variable : conditionVariables(loop.getCondition())) {
            String agent = boundBy.get(variable);
            if (agent == null) continue; // not an agent's answer: a task, a check or a person decides
            boolean producesOtherWork = false;
            for (String other : bound.getOrDefault(agent, List.of()))
                if (!other.equals(variable) && readAnywhere.contains(other)) producesOtherWork = true;
            boolean iteratesOnItsOwnAnswer = false;
            for (Statement s : body)
                if (s instanceof DelegateStmt d
                        && !d.hasDynamicTarget()
                        && agent.equals(d.getTargetAgent())
                        && placeholders(d.getPayload()).contains(variable)) iteratesOnItsOwnAnswer = true;
            if (producesOtherWork || iteratesOnItsOwnAnswer) {
                mark(out, Kinds.LOOP, loop.getLine(), "decidesItsOwnLoop", true, "loopDecider", agent);
                return;
            }
        }
    }

    /** The variables a condition reads: the first name of each dotted path, outside quotes. */
    private static Set<String> conditionVariables(String condition) {
        Set<String> names = new LinkedHashSet<>();
        if (condition == null) return names;
        String bare = condition.replaceAll("\"[^\"]*\"", " ");
        Matcher m = WORD.matcher(bare);
        Set<String> words = Set.of("and", "or", "not", "true", "false", "missing", "contains", "in", "is");
        while (m.find()) {
            int before = m.start() - 1;
            if (before >= 0 && bare.charAt(before) == '.') continue; // a field, not a variable
            if (!words.contains(m.group())) names.add(m.group());
        }
        return names;
    }

    // ── checks ───────────────────────────────────────────────────────────────────────────────

    private static void check(
            FactBase.Builder out, CheckStmt check, LoomScript script, Map<String, AgentDef> agents, Map<String, String> writer) {
        EvaluatorDef evaluator = script.findEvaluator(check.getEvaluator());
        String agentName = writer.get(check.getSubject());
        AgentDef agent = agentName == null ? null : agents.get(agentName);
        if (evaluator == null || agent == null || evaluator.getModel() == null) return;
        if (evaluator.getModel().equals(agent.getModel()))
            mark(out, Kinds.CHECK, check.getLine(), "checkedByOwnModel", true, "checkedAgent", agentName);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private static Set<String> placeholders(List<Statement> statements) {
        Set<String> names = new HashSet<>();
        for (Statement s : statements) {
            if (s instanceof DelegateStmt d) names.addAll(placeholders(d.getPayload()));
            if (s instanceof ReturnStmt r && r.getValue() != null) valueNames(r.getValue(), names);
        }
        return names;
    }

    private static void valueNames(ValueExpr value, Set<String> names) {
        if (value instanceof ValueExpr.Text t) names.addAll(placeholders(t.text()));
        else if (value instanceof ValueExpr.Ref r) names.add(r.path().split("\\.")[0]);
        else if (value instanceof ValueExpr.Obj o) o.fields().values().forEach(v -> valueNames(v, names));
    }

    private static Set<String> placeholders(String text) {
        Set<String> names = new HashSet<>();
        if (text == null) return names;
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) names.add(m.group(1));
        return names;
    }

    private static void mark(FactBase.Builder out, String kind, int line, String flag, boolean value, String name, String agent) {
        for (Entity step : out.entitiesOf("step")) {
            if (!kind.equals(String.valueOf(step.get("kind")))) continue;
            if (!(step.get("line") instanceof Long l) || l != line) continue;
            out.set(step, flag, value);
            out.set(step, name, agent);
        }
    }
}
