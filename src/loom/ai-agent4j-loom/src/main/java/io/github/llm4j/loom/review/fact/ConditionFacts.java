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
        Map<String, Map<String, Integer>> varsOf =
                new HashMap<>(); // workflow -> variable -> first line bound
        for (WorkflowDef w : input.review().script().getWorkflows()) {
            Map<String, Integer> names = varsOf.computeIfAbsent(w.getName(), k -> new HashMap<>());
            for (String p : w.getParameters()) names.put(p, 0);
            StatementWalker.walk(
                    w.getStatements(),
                    s -> {
                        String v = bound(s);
                        if (v != null) names.merge(v, s.getLine(), Math::min);
                    });
        }
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
            out.set(step, "payloadHitsVariable", s == null ? "" : payloadHit(s, step, varsOf));
            String payload = s == null ? null : payloadOf(s);
            out.set(
                    step,
                    "payloadMentionsPersonal",
                    payload != null && mentionsPersonal(payload, input.settings()));
            out.set(
                    step,
                    "payloadSaysDeterministic",
                    payload != null && DETERMINISTIC.matcher(literal(payload)).find());
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
                    case "delegate" -> "DelegateStmt";
                    case "broadcast" -> "BroadcastStmt";
                    case "handoff" -> "HandoffStmt";
                    default -> null;
                };
        if (cls == null) return null;
        return byKey.get(w.get("name") + ":" + step.get("line") + ":" + cls);
    }

    private static final Pattern DETERMINISTIC =
            Pattern.compile(
                    "(?i)\\b(calculate|compute the (sum|total|average)|add up the numbers|validate the format|check against the (policy )?table|convert (the )?(currency|units)|sort the list|count the)\\b");
    private static final Pattern PERSONAL =
            Pattern.compile(
                    "(?i)(email|phone|address|dob|ssn|card|(first|last|full|customer|user)_?name)");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_.]*)}");

    private static String payloadOf(Statement s) {
        if (s instanceof DelegateStmt d) return d.getPayload();
        if (s instanceof BroadcastStmt b) return b.getPayload();
        if (s instanceof io.github.llm4j.loom.ast.HandoffStmt h) return h.getPayload();
        return null;
    }

    private static String literal(String payload) {
        return payload.replaceAll("\\{[^}]*}", " ");
    }

    /**
     * True when the prompt fills in a value whose name says it is personal data (email, phone,
     * address, dob, ssn, card, a person's name), or that the project lists in {@code
     * data.personal}.
     */
    private static boolean mentionsPersonal(
            String payload, io.github.llm4j.loom.review.settings.ReviewSettings settings) {
        java.util.List<String> declared =
                settings.value("data.personal")
                        .map(
                                v ->
                                        java.util.Arrays.stream(v.split(","))
                                                .map(String::strip)
                                                .filter(x -> !x.isEmpty())
                                                .toList())
                        .orElse(java.util.List.of());
        Matcher m = PLACEHOLDER.matcher(payload);
        while (m.find()) {
            String path = m.group(1);
            String last = path.substring(path.lastIndexOf('.') + 1);
            if (PERSONAL.matcher(last).find() || declared.contains(path) || declared.contains(last))
                return true;
        }
        return false;
    }

    private static String bound(Statement s) {
        if (s instanceof DelegateStmt d) return d.getVariableName();
        if (s instanceof BroadcastStmt b) return b.getVariableName();
        if (s instanceof io.github.llm4j.loom.ast.IntentClassifyStmt c) return c.getVariableName();
        if (s instanceof io.github.llm4j.loom.ast.HumanPromptStmt h) return h.getVariableName();
        return null;
    }

    /**
     * The name of a workflow variable that appears inside the literal text of a prompt. Loom
     * replaces a bare variable name wherever it occurs in the text a script wrote, even inside
     * another word, so a variable called {@code r} rewrites every "r" in the sentence.
     */
    private static String payloadHit(
            Statement s, Entity step, Map<String, Map<String, Integer>> varsOf) {
        String payload =
                s instanceof DelegateStmt d
                        ? d.getPayload()
                        : s instanceof BroadcastStmt b
                                ? b.getPayload()
                                : s instanceof io.github.llm4j.loom.ast.HandoffStmt h
                                        ? h.getPayload()
                                        : null;
        if (payload == null || !(step.get("workflow") instanceof Entity w)) return "";
        String literal = payload.replaceAll("\\{[^}]*}", " ");
        Map<String, Integer> names = varsOf.getOrDefault((String) w.get("name"), Map.of());
        boolean repeats =
                Boolean.TRUE.equals(step.get("inLoop"))
                        || Boolean.TRUE.equals(step.get("inForEach"));
        // a variable counts when it is set before this step, or when the step repeats and sets it
        return names.entrySet().stream()
                .filter(e -> !e.getKey().isEmpty() && literal.contains(e.getKey()))
                .filter(e -> e.getValue() < s.getLine() || repeats)
                .map(Map.Entry::getKey)
                .sorted()
                .findFirst()
                .orElse("");
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
