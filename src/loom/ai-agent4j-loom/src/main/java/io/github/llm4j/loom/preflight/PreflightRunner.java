package io.github.llm4j.loom.preflight;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.budget.BudgetExceeded;
import io.github.llm4j.budget.BudgetSet;
import io.github.llm4j.budget.PriceTable;
import io.github.llm4j.context.ContextWindows;
import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LLMClientFactory;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The cheap live check between "the script checks clean" and "build the dataset". For each agent
 * it sends one probe through the agent's real prompts and reports, per check, pass, warn, fail or
 * skipped, with the one fix: does the key resolve, did the provider accept the model, is one
 * answer whole, does the shared prompt reach the model (a random canary placed in it, in memory
 * only), do grounded agents return sources, are the price and window known. It stays under its own
 * cap and stores no answer text.
 */
public final class PreflightRunner {
    /** What a check found. */
    public enum Result {
        PASS,
        WARN,
        FAIL,
        SKIPPED
    }

    /**
     * One check of one agent ({@code agent} is null for a check about the whole script).
     *
     * @param id C1 to C7
     * @param fix the one thing to do about it; empty when it passed
     */
    public record Check(String id, String agent, Result result, String detail, String fix) {}

    /** Makes an executor that answers as the real script would. */
    public interface Executors {
        /**
         * @param canaryInstruction text to add to the shared prompt in memory, or null for none
         * @param tokenCap what is left of the token cap, or null for none
         * @param costCap what is left of the cost cap, or null for none
         */
        HarnessExecutor create(String canaryInstruction, Long tokenCap, BigDecimal costCap);
    }

    /**
     * @param cost what the probes cost when prices are known, else null (never zero by default)
     * @param stoppedByCap whether the cap stopped the preflight before every agent was probed
     */
    public record Report(
            List<Check> checks,
            long tokens,
            BigDecimal cost,
            boolean stoppedByCap,
            String at,
            String build) {

        /** 0 every check passed or warned, 1 something failed, 3 the cap stopped it. */
        public int exitCode() {
            if (stoppedByCap) return 3;
            return checks.stream().anyMatch(c -> c.result() == Result.FAIL) ? 1 : 0;
        }

        public String toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", at);
            m.put("build", build);
            m.put("tokens", tokens);
            if (cost != null) m.put("cost", cost.toPlainString());
            m.put("stoppedByCap", stoppedByCap);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Check c : checks) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", c.id());
                if (c.agent() != null) row.put("agent", c.agent());
                row.put("result", c.result().name().toLowerCase(Locale.ROOT));
                row.put("detail", clip(c.detail()));
                if (!c.fix().isEmpty()) row.put("fix", clip(c.fix()));
                rows.add(row);
            }
            m.put("checks", rows);
            try {
                return new ObjectMapper().writeValueAsString(m);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }

        public String toText() {
            StringBuilder b = new StringBuilder();
            for (Check c : checks) {
                b.append(String.format(Locale.ROOT, "%-7s %s %-10s %s%n", c.result().name().toLowerCase(Locale.ROOT), c.id(), c.agent() == null ? "(script)" : c.agent(), c.detail()));
                if (!c.fix().isEmpty()) b.append("                      fix: ").append(c.fix()).append('\n');
            }
            b.append("Spent ").append(tokens).append(" tokens");
            b.append(cost == null ? " (no prices given, so no dollar figure)" : ", $" + cost.setScale(4, java.math.RoundingMode.HALF_UP).toPlainString());
            b.append(stoppedByCap ? ". It stopped at its own cap." : ".").append('\n');
            return b.toString();
        }
    }

    private static final int DETAIL_LIMIT = 200;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PreflightRunner() {}

    private static String clip(String text) {
        return text.length() <= DETAIL_LIMIT ? text : text.substring(0, DETAIL_LIMIT - 1) + "…";
    }

    /**
     * Runs the checks.
     *
     * @param probeFor the probe input for an agent (the first dataset case for it, else a default)
     * @param tokenCap the most tokens the probes may use together, or null
     * @param costCap the most they may cost together (needs prices), or null
     */
    public static Report run(
            LoomScript script,
            Executors executors,
            LLMClientFactory models,
            Function<String, String> probeFor,
            Long tokenCap,
            BigDecimal costCap,
            PriceTable prices,
            ContextWindows windows,
            Clock clock,
            String build) {
        List<Check> checks = new ArrayList<>();
        boolean shared = script.getSharedPrompt() != null || script.getSharedPromptRef() != null;
        String canary = "CANARY-" + HexFormat.of().formatHex(randomBytes());
        String instruction =
                shared ? "Begin your answer with exactly " + canary + " and then answer as you normally would." : null;
        long used = 0;
        BigDecimal spent = null;
        boolean stopped = false;
        for (AgentDef agent : script.getAgents()) {
            String name = agent.getName();
            String model = agent.getModel();
            String problem = model == null || model.isBlank() ? "the agent names no model" : models.problem(model);
            if (problem != null) {
                checks.add(new Check("C1", name, Result.FAIL, "the key or model does not resolve: " + problem, problem));
                skipRest(checks, name, "no key");
            } else if (stopped) {
                checks.add(new Check("C1", name, Result.PASS, "the key resolves", ""));
                skipRest(checks, name, "stopped by its own cap before this agent");
            } else {
                checks.add(new Check("C1", name, Result.PASS, "the key resolves", ""));
                Long tokensLeft = tokenCap == null ? null : Math.max(1, tokenCap - used);
                BigDecimal costLeft = costCap == null ? null : costCap.subtract(spent == null ? BigDecimal.ZERO : spent).max(new BigDecimal("0.000001"));
                HarnessExecutor executor = executors.create(instruction, tokensLeft, costLeft);
                try {
                    AgentResult result = executor.runAgentTask(name, probeFor.apply(name));
                    long agentTokens = executor.usageByAgent().values().stream().mapToLong(u -> u.promptTokens() + u.completionTokens()).sum();
                    used += agentTokens;
                    try {
                        BigDecimal c = executor.spend().total().cost();
                        if (c != null) spent = spent == null ? c : spent.add(c);
                    } catch (RuntimeException e) {
                        // no price: no figure
                    }
                    if (result.budgetExhausted()) {
                        // the agent ran out of the cap part-way; what it wrote is not a whole answer
                        stopped = true;
                        skipRest(checks, name, "stopped by its own cap");
                    } else {
                        answered(checks, agent, result, executor, instruction != null ? canary : null, windows);
                    }
                } catch (BudgetExceeded cap) {
                    stopped = true;
                    skipRest(checks, name, "stopped by its own cap (" + cap.getMessage() + ")");
                } catch (RuntimeException e) {
                    String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    checks.add(new Check("C2", name, Result.FAIL, "the provider did not answer: " + why, "check the model name and the key's access to it"));
                    for (String id : List.of("C3", "C4", "C5")) checks.add(new Check(id, name, Result.SKIPPED, "no answer to check", ""));
                } finally {
                    executor.shutdown();
                }
            }
            checks.add(priceAndWindow(name, model, prices, windows));
        }
        checks.add(budget(script, windows));
        return new Report(checks, used, prices == null ? null : spent, stopped, clock.instant().toString(), build);
    }

    private static byte[] randomBytes() {
        byte[] b = new byte[4];
        RANDOM.nextBytes(b);
        return b;
    }

    private static void skipRest(List<Check> checks, String agent, String why) {
        for (String id : List.of("C2", "C3", "C4", "C5")) checks.add(new Check(id, agent, Result.SKIPPED, why, ""));
    }

    private static void answered(
            List<Check> checks,
            AgentDef agent,
            AgentResult result,
            HarnessExecutor executor,
            String canary,
            ContextWindows windows) {
        String name = agent.getName();
        checks.add(new Check("C2", name, Result.PASS, "the provider accepted the model and answered", ""));
        String answer = result.getFinalAnswer() == null ? "" : result.getFinalAnswer();
        long cut = executor.truncatedAnswers().getOrDefault(name, 0L);
        long limit = windows.window(agent.getModel()).map(w -> (long) w.reservedForOutput()).filter(l -> l > 0).orElse(BudgetSet.DEFAULT_OUTPUT);
        if (answer.isBlank())
            checks.add(new Check("C3", name, Result.FAIL, "the answer was empty", "run the agent once with weave run and read what comes back"));
        else if (cut > 0)
            checks.add(new Check("C3", name, Result.FAIL, "the answer was cut at the output limit (about " + limit + " tokens)", "raise per_call, or shorten what the agent is asked for"));
        else checks.add(new Check("C3", name, Result.PASS, "one answer ended by itself (the output limit is about " + limit + " tokens)", ""));
        if (canary == null) checks.add(new Check("C4", name, Result.SKIPPED, "the script has no shared_prompt", ""));
        else if (answer.stripLeading().startsWith(canary)) checks.add(new Check("C4", name, Result.PASS, "the shared prompt reached the model: it began its answer as told", ""));
        else if (answer.contains(canary)) checks.add(new Check("C4", name, Result.WARN, "the canary appeared, but not at the start of the answer", "the model follows the shared prompt loosely: put the rule that matters in the agent's own prompt as well"));
        else checks.add(new Check("C4", name, Result.FAIL, "the answer does not begin with the canary, so the shared prompt did not take effect (Loom's offline tests prove it is sent; if they pass, the model ignored it)", "move the rule that matters into the agent's own prompt, or use a model that follows system instructions"));
        if (agent.getGrounding() == null) checks.add(new Check("C5", name, Result.SKIPPED, "the agent is not grounded", ""));
        else {
            int sources = result.getCitations().size();
            long searches = result.getStats() == null ? 0 : result.getStats().totalSearches();
            checks.add(sources > 0
                    ? new Check("C5", name, Result.PASS, sources + " source" + (sources == 1 ? "" : "s") + " came back (" + searches + " search" + (searches == 1 ? "" : "es") + ")", "")
                    : new Check("C5", name, Result.WARN, "grounded but no sources came back (searches: " + searches + ")", "the model may not have searched: check the grounding settings and the question"));
        }
    }

    private static Check priceAndWindow(String agent, String model, PriceTable prices, ContextWindows windows) {
        boolean price = prices != null && model != null && prices.price(model).isPresent();
        boolean window = model != null && windows.window(model).isPresent();
        if (price && window) return new Check("C6", agent, Result.PASS, "the price and the context window are known", "");
        List<String> missing = new ArrayList<>();
        if (!price) missing.add("price");
        if (!window) missing.add("context window");
        return new Check("C6", agent, Result.WARN, "unknown for " + model + ": " + String.join(" and ", missing),
                (price ? "" : "give --prices <file> with a line 'model = input / output' per million tokens") + (price || window ? "" : "; ") + (window ? "" : "list the model's window in a --context-windows file"));
    }

    private static Check budget(LoomScript script, ContextWindows windows) {
        long biggest = script.getAgents().stream().map(a -> windows.window(a.getModel() == null ? "" : a.getModel()).map(w -> (long) w.reservedForOutput()).filter(l -> l > 0).orElse(BudgetSet.DEFAULT_OUTPUT)).max(Long::compare).orElse(BudgetSet.DEFAULT_OUTPUT);
        var budget = script.getBudget();
        if (budget == null || budget.getTokens() == null)
            return new Check("C7", null, Result.PASS, "one answer may be up to about " + biggest + " tokens; the script sets no token budget", "");
        if (budget.getTokens() < biggest)
            return new Check("C7", null, Result.WARN, "the run budget (" + budget.getTokens() + " tokens) is smaller than one answer may be (about " + biggest + "), so the budget will bite first", "raise the run budget or set per_call on the agents");
        return new Check("C7", null, Result.PASS, "the run budget (" + budget.getTokens() + " tokens) is larger than one answer (about " + biggest + ")", "");
    }
}
