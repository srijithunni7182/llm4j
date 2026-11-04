package io.github.llm4j.loom.execution;

import io.github.llm4j.agent.ToolHook;
import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.EvaluatorDef;
import io.github.llm4j.loom.ast.PolicyDef;
import io.github.llm4j.loom.policy.PolicyCatalog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Checks an agent's own calls to tools that change things, before they run and before a person is
 * asked (docs/specs/26-policy-boundary, R8, D25). The agent writes the call's arguments inside its
 * own loop, where no {@code check} statement can stand, so the runtime sends the call's text
 * arguments to the evaluator each policy names ({@code checked by}), exactly as a {@code check}
 * statement would send them.
 *
 * <p>A pass lets the call go on (to {@code approve:} where listed). A violation, an unsure rule or
 * an evaluator that cannot answer refuses the call: the tool does not run, and the agent is told the
 * rule ids and the evaluator's one-line reasons, never the rule text. After {@link #MAX_REFUSALS}
 * refusals in one step every further call is refused and the agent is told to stop. A call with no
 * text argument is not sent to an evaluator: a policy judges what a call says, never how much it
 * moves.
 */
final class PolicyToolHook implements ToolHook {

    /** Refusals one agent may get in one step before it is told to stop trying. */
    static final int MAX_REFUSALS = 2;

    private final HarnessExecutor executor;
    private final AgentDef agent;
    private final Set<String> changing;
    private final boolean everythingUnknownChanges;
    private final Map<String, AtomicInteger> refusals = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * @param changing the names of the agent's tools whose effect is {@code changes}
     * @param everythingUnknownChanges the agent holds an MCP server: a tool it names that is not in
     *     the script is assumed to change things
     */
    PolicyToolHook(
            HarnessExecutor executor,
            AgentDef agent,
            Set<String> changing,
            boolean everythingUnknownChanges) {
        this.executor = executor;
        this.agent = agent;
        this.changing = Set.copyOf(changing);
        this.everythingUnknownChanges = everythingUnknownChanges;
    }

    @Override
    public Decision beforeCall(String toolName, Map<String, Object> arguments) {
        if (!changing.contains(toolName)
                && !(everythingUnknownChanges && !executor.isDeclaredTool(toolName))) {
            return Decision.allow();
        }
        String text = textOf(arguments);
        if (text.isEmpty()) return Decision.allow();

        String step = executor.currentStep();
        AtomicInteger count = refusals.computeIfAbsent(step, k -> new AtomicInteger());
        if (count.get() >= MAX_REFUSALS) {
            return refuse(
                    toolName,
                    List.of(),
                    "Stop: this step has already had "
                            + MAX_REFUSALS
                            + " calls refused by policy. Do not call "
                            + toolName
                            + " again; answer without it.",
                    count.get());
        }

        List<PolicyDef> policies = executor.script().getPolicies();
        Map<String, List<PolicyCatalog.Resolved>> byEvaluator = new LinkedHashMap<>();
        PolicyCatalog catalog = PolicyCatalog.beside(executor.baseDir());
        for (PolicyDef def : policies) {
            if (def.getCheckedBy() == null) continue; // weave check reports it
            var resolved = catalog.resolve(def);
            if (resolved.isEmpty())
                return refuse(toolName, List.of(), "policy " + def.getName() + " could not be read, so the call was not run.", count.incrementAndGet());
            byEvaluator.computeIfAbsent(def.getCheckedBy(), k -> new ArrayList<>()).add(resolved.get());
        }
        List<String> broken = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        for (Map.Entry<String, List<PolicyCatalog.Resolved>> e : byEvaluator.entrySet()) {
            EvaluatorDef evaluator = executor.script().findEvaluator(e.getKey());
            String sent =
                    PiiGuard.apply(
                            executor,
                            "evaluator",
                            evaluator.getName(),
                            evaluator.getPii(),
                            "the arguments of " + toolName,
                            "the arguments for " + evaluator.getName(),
                            "the model",
                            text);
            PolicyEvaluator.Verdict verdict =
                    PolicyEvaluator.evaluate(
                            evaluator,
                            e.getValue(),
                            sent,
                            model -> executor.clientForEvaluator(evaluator, model));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("at", "tool");
            data.put("tool", toolName);
            data.put("agent", agent.getName());
            data.put("evaluator", evaluator.getName());
            data.put("violated", verdict.violated());
            data.put("unsure", verdict.unsure());
            data.put("unavailable", verdict.unavailable());
            executor.trace(TraceEvent.POLICY_CHECK, null, toolName + " → " + (verdict.passed() ? "none" : "refused"), data);
            if (verdict.passed()) continue;
            if (verdict.unavailable()) {
                broken.add("(evaluator unavailable)");
            } else {
                for (PolicyEvaluator.Rule r : verdict.rules().values()) {
                    if (r.answer().equals("no")) continue;
                    broken.add(r.id());
                    if (!r.reason().isBlank()) reasons.add(r.id() + ": " + r.reason());
                }
            }
        }
        if (broken.isEmpty()) return Decision.allow();
        return refuse(
                toolName,
                broken,
                "This call was refused by policy and was not run. Rules: "
                        + String.join(", ", broken)
                        + (reasons.isEmpty() ? "" : ". " + String.join("; ", reasons))
                        + ". Write the call again so that it does not break them.",
                count.incrementAndGet());
    }

    private Decision refuse(String tool, List<String> rules, String message, int count) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agent", agent.getName());
        data.put("tool", tool);
        data.put("rules", rules);
        data.put("refusals", count);
        executor.trace(TraceEvent.TOOL_REFUSED_BY_POLICY, agent.getName(), tool + " refused", data);
        executor.audit("policy_violation", data);
        return Decision.reject(message, io.github.llm4j.telemetry.ErrorCategory.OTHER);
    }

    /** The call's model-written text: strings at any depth. Numbers and true/false are not text. */
    static String textOf(Map<String, Object> arguments) {
        StringBuilder out = new StringBuilder();
        collect("", arguments, out);
        return out.toString().strip();
    }

    private static void collect(String key, Object value, StringBuilder out) {
        if (value instanceof CharSequence s) {
            if (!s.toString().isBlank())
                out.append(key.isEmpty() ? "" : key + ": ").append(s).append('\n');
        } else if (value instanceof Map<?, ?> m) {
            m.forEach((k, v) -> collect(key.isEmpty() ? String.valueOf(k) : key + "." + k, v, out));
        } else if (value instanceof Iterable<?> it) {
            for (Object o : it) collect(key, o, out);
        }
    }
}
