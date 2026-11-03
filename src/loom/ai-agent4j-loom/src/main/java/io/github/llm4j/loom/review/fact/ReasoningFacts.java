package io.github.llm4j.loom.review.fact;

import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.ForEachStmt;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.LoopStmt;
import io.github.llm4j.loom.ast.SchemaDef;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.WorkflowDef;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Whether an agent has to think before it answers, and whether it is asked to answer in a big
 * schema (docs/specs/25-reason-then-extract, R1). Both are read from the structure of the script,
 * never from what a prompt means. An agent <em>reasons</em> when it has tools, has grounding, works
 * over rounds (a delegate in a loop or for each feeds the agent's own earlier answer back to it),
 * or has a prompt longer than {@code prompts.short-prompt-tokens}. A schema is <em>large</em> when
 * it has a nested object, a list of objects, or more than {@code prompts.large-schema-fields}
 * fields at the top. A prompt whose size is unknown is not judged on size.
 */
final class ReasoningFacts implements FactProvider {

    private static final int DEFAULT_SHORT_PROMPT = 300;
    private static final int DEFAULT_LARGE_FIELDS = 5;

    @Override
    public String name() {
        return "reasoning";
    }

    @Override
    public void provide(FactInput input, FactBase.Builder out) {
        LoomScript script = input.review().script();
        long shortPrompt = number(input, "prompts.short-prompt-tokens", DEFAULT_SHORT_PROMPT);
        long largeFields = number(input, "prompts.large-schema-fields", DEFAULT_LARGE_FIELDS);
        Map<String, List<SchemaDef>> expecting = new HashMap<>();
        Set<String> rounds = new HashSet<>();
        for (WorkflowDef w : script.getWorkflows()) {
            StatementWalker.walk(
                    w.getStatements(),
                    s -> {
                        if (s instanceof DelegateStmt d
                                && d.getExpecting() != null
                                && d.getTargetAgent() != null)
                            expecting
                                    .computeIfAbsent(d.getTargetAgent(), k -> new ArrayList<>())
                                    .add(d.getExpecting());
                        if (s instanceof LoopStmt l) rounds.addAll(feedBack(l.getBody()));
                        if (s instanceof ForEachStmt f) rounds.addAll(feedBack(f.getBody()));
                    });
        }
        for (AgentDef a : script.getAgents()) {
            Entity e = out.find("agent:" + a.getName()).orElse(null);
            if (e == null) continue;
            out.set(e, "reasons", reasonsBecause(e, a, rounds, shortPrompt) != null);
            String why = reasonsBecause(e, a, rounds, shortPrompt);
            out.set(e, "reasonsBecause", why == null ? "" : why);
            List<SchemaDef> schemas = new ArrayList<>(expecting.getOrDefault(a.getName(), List.of()));
            if (a.getOutputSchema() != null) schemas.add(0, a.getOutputSchema());
            String large = null;
            for (SchemaDef s : schemas) {
                large = largeBecause(s, largeFields);
                if (large != null) break;
            }
            out.set(e, "largeSchema", large != null);
            out.set(e, "largeSchemaBecause", large == null ? "" : large);
        }
    }

    private static String reasonsBecause(Entity e, AgentDef a, Set<String> rounds, long shortPrompt) {
        if (!a.getTools().isEmpty() || !a.getMcpServers().isEmpty()) return "it has tools";
        if (a.getGrounding() != null) return "it searches the web";
        if (rounds.contains(a.getName())) return "it works over rounds";
        Object tokens = e.get("promptTokens");
        if (tokens instanceof Number n && n.longValue() > shortPrompt)
            return "it has a prompt of about " + n.longValue() + " tokens";
        return null;
    }

    /** What makes the schema large, in words, or null when it is small and flat. */
    static String largeBecause(SchemaDef schema, long largeFields) {
        if (schema == null || schema.getType() != SchemaDef.Type.OBJECT) return null;
        for (SchemaDef field : schema.getFields().values()) {
            if (field.getType() == SchemaDef.Type.OBJECT) return "a nested object";
            if (field.getType() == SchemaDef.Type.LIST
                    && field.getElementType() != null
                    && field.getElementType().getType() == SchemaDef.Type.OBJECT)
                return "a list of objects";
        }
        if (schema.getFields().size() > largeFields)
            return schema.getFields().size() + " fields";
        return null;
    }

    /**
     * The agents a loop body asks again with their own earlier answer: a delegate to the agent
     * whose task writes a variable that a delegate to the same agent in the body binds.
     */
    private static Set<String> feedBack(List<Statement> body) {
        Map<String, Set<String>> boundBy = new HashMap<>();
        List<DelegateStmt> delegates = new ArrayList<>();
        StatementWalker.walk(
                body,
                s -> {
                    if (s instanceof DelegateStmt d && d.getTargetAgent() != null) {
                        delegates.add(d);
                        if (d.getVariableName() != null)
                            boundBy
                                    .computeIfAbsent(d.getTargetAgent(), k -> new HashSet<>())
                                    .add(d.getVariableName());
                    }
                });
        Set<String> out = new HashSet<>();
        for (DelegateStmt d : delegates)
            for (String variable : boundBy.getOrDefault(d.getTargetAgent(), Set.of()))
                if (d.getPayload() != null
                        && Pattern.compile("\\{" + Pattern.quote(variable) + "[.}]")
                                .matcher(d.getPayload())
                                .find()) out.add(d.getTargetAgent());
        return out;
    }

    private static long number(FactInput input, String key, long fallback) {
        try {
            return input.settings().value(key).map(Long::parseLong).orElse(fallback);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
