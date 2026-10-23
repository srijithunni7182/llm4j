package io.github.llm4j.loom.review.cost;

import io.github.llm4j.budget.PriceTable;
import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.AltStmt;
import io.github.llm4j.loom.ast.BroadcastStmt;
import io.github.llm4j.loom.ast.CallStmt;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.ForEachStmt;
import io.github.llm4j.loom.ast.GuardrailStmt;
import io.github.llm4j.loom.ast.HandoffStmt;
import io.github.llm4j.loom.ast.IntentClassifierDef;
import io.github.llm4j.loom.ast.IntentClassifyStmt;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.LoopStmt;
import io.github.llm4j.loom.ast.ParallelStmt;
import io.github.llm4j.loom.ast.RewindStmt;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.ast.WorkflowDef;
import io.github.llm4j.loom.prompt.PromptCatalog;
import io.github.llm4j.loom.prompt.PromptSupport;
import io.github.llm4j.loom.review.engine.ReviewInput;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Estimates what each workflow of a script spends, from the script alone. It never calls a model
 * and never counts tokens with a provider, so the same inputs always give the same figures.
 *
 * <p>Two figures are kept side by side. <b>Typical</b> uses the assumptions (a loop turns three
 * times, a {@code for each} walks five items, one attempt per step). <b>Worst</b> uses the most the
 * script allows (a loop's {@code max}, every retry, the larger {@code for each}); where the script
 * allows no most, the workflow is marked unbounded and no made-up number is given.
 */
public final class CostEstimator {

    private final PriceTable prices;
    private final String priceLabel;

    /** An estimator with no prices: tokens are estimated, money is reported as unpriced. */
    public CostEstimator() {
        this(null, null);
    }

    public CostEstimator(PriceTable prices, String priceLabel) {
        this.prices = prices;
        this.priceLabel = priceLabel;
    }

    public CostEstimate estimate(ReviewInput input, ReviewSettings settings) {
        Assumptions assume = Assumptions.of(settings);
        LoomScript script = input.script();
        Walk walk = new Walk(script, input, assume);
        List<WorkflowCost> out = new ArrayList<>();
        for (WorkflowDef w : script.getWorkflows()) out.add(walk.workflow(w));
        var budget = script.getBudget();
        return new CostEstimate(
                out,
                assume.used(),
                prices,
                priceLabel,
                budget == null ? null : budget.getCost(),
                budget == null ? null : budget.getTokens());
    }

    /** What the walk of one script accumulates. */
    private static final class Walk {
        final LoomScript script;
        final Assumptions assume;
        final PromptCatalog prompts;
        final Map<String, AgentDef> agents;
        final Map<String, IntentClassifierDef> classifiers;
        final Map<String, WorkflowDef> workflows;
        final Set<String> stack = new HashSet<>();

        List<StepCost> steps;
        List<String> notes;
        String current;

        Walk(LoomScript script, ReviewInput input, Assumptions assume) {
            this.script = script;
            this.assume = assume;
            PromptCatalog catalog = null;
            try {
                catalog =
                        PromptSupport.catalog(script, input.baseDir().resolve(input.file()), null);
            } catch (RuntimeException e) {
                // no prompt folder or an unreadable one: file prompts fall back to the assumption
            }
            this.prompts = catalog;
            this.agents =
                    script.getAgents().stream()
                            .collect(Collectors.toMap(AgentDef::getName, a -> a, (a, b) -> a));
            this.classifiers =
                    script.getIntentClassifiers().stream()
                            .collect(
                                    Collectors.toMap(
                                            IntentClassifierDef::getName, c -> c, (a, b) -> a));
            this.workflows =
                    script.getWorkflows().stream()
                            .collect(Collectors.toMap(WorkflowDef::getName, w -> w, (a, b) -> a));
        }

        WorkflowCost workflow(WorkflowDef w) {
            steps = new ArrayList<>();
            notes = new ArrayList<>();
            current = w.getName();
            stack.clear();
            stack.add(w.getName());
            Bound b = block(w.getStatements(), Mult.ONE);
            long rewinds = rewindBudget(w.getStatements());
            Usage worst = b.worst.times(1 + rewinds);
            if (rewinds > 0)
                notes.add(
                        "rewind may re-run work up to "
                                + rewinds
                                + " times; the worst case allows for it by repeating the whole workflow that many times more");
            return new WorkflowCost(w.getName(), b.typical, worst, b.unbounded, steps, notes);
        }

        /** Sum of every {@code at most M times} in a block, however deep. */
        long rewindBudget(List<Statement> list) {
            long[] sum = {0};
            io.github.llm4j.loom.ast.StatementWalker.walk(
                    list,
                    s -> {
                        if (s instanceof RewindStmt r) sum[0] += Math.max(r.getAtMost(), 0);
                    });
            return sum[0];
        }

        /** How often a statement can run: typically and at most ({@code -1}: no bound). */
        record Mult(long typical, long worst) {
            static final Mult ONE = new Mult(1, 1);

            Mult times(long t, long w) {
                return new Mult(typical * t, worst < 0 || w < 0 ? -1 : worst * w);
            }
        }

        /** The cost of a block: typical, worst, and whether the worst has no bound. */
        record Bound(Usage typical, Usage worst, boolean unbounded) {
            static final Bound NONE = new Bound(Usage.NONE, Usage.NONE, false);

            Bound plus(Bound o) {
                return new Bound(
                        typical.plus(o.typical), worst.plus(o.worst), unbounded || o.unbounded);
            }

            Bound times(long t, long w, boolean boundless) {
                return new Bound(typical.times(t), worst.times(w), unbounded || boundless);
            }
        }

        Bound block(List<Statement> list, Mult m) {
            Bound total = Bound.NONE;
            for (Statement s : list == null ? List.<Statement>of() : list)
                total = total.plus(statement(s, m));
            return total;
        }

        Bound statement(Statement s, Mult m) {
            if (s instanceof DelegateStmt d) return delegate(d, m);
            if (s instanceof BroadcastStmt b) return broadcast(b, m);
            if (s instanceof HandoffStmt h)
                return step(
                        s.getLine(),
                        "handoff " + h.getTargetAgent(),
                        h.getTargetAgent(),
                        h.getPayload(),
                        m,
                        0);
            if (s instanceof IntentClassifyStmt c) return classify(c, m);
            if (s instanceof AltStmt a) return alt(a, m);
            if (s instanceof LoopStmt l) return loop(l, m);
            if (s instanceof ForEachStmt f) return forEach(f, m);
            if (s instanceof ParallelStmt p) return block(p.getBody(), m);
            if (s instanceof GuardrailStmt g)
                return block(g.getBody(), m).plus(block(g.getOnViolation(), m));
            if (s instanceof CallStmt c) return call(c, m);
            return Bound.NONE;
        }

        Bound delegate(DelegateStmt d, Mult m) {
            String label = "delegate " + (d.hasDynamicTarget() ? "(dynamic)" : d.getTargetAgent());
            Bound self =
                    step(
                            d.getLine(),
                            label,
                            d.getTargetAgent(),
                            d.getPayload(),
                            m,
                            d.getRetryCount());
            Bound failure = block(d.getOnFailure(), new Mult(0, m.worst));
            return self.plus(new Bound(Usage.NONE, failure.worst, failure.unbounded));
        }

        Bound broadcast(BroadcastStmt b, Mult m) {
            Bound total = Bound.NONE;
            for (String a : b.getTargetAgents())
                total = total.plus(step(b.getLine(), "broadcast " + a, a, b.getPayload(), m, 0));
            return total;
        }

        Bound classify(IntentClassifyStmt c, Mult m) {
            IntentClassifierDef def = classifiers.get(c.getClassifier());
            if (def == null || def.getModel() == null) {
                notes.add("classifier " + c.getClassifier() + " has no model to price");
                return Bound.NONE;
            }
            long in = c.getInputs().size() * assume.get("assume.variable.tokens") + 30;
            int samples = Math.max(1, def.getSamples());
            Usage each = Usage.calls(def.getModel(), samples, in * samples, 10L * samples);
            long attempts = c.getRetryCount() + 1L;
            steps.add(
                    new StepCost(
                            current,
                            c.getLine(),
                            "intentclassify " + c.getClassifier(),
                            def.getModel(),
                            each,
                            each,
                            m.typical,
                            m.worst < 0 ? -1 : m.worst * attempts));
            return bound(each, each, m, c.getRetryCount());
        }

        Bound alt(AltStmt a, Mult m) {
            Bound yes = block(a.getIfBranch(), m);
            Bound no = block(a.getElseBranch(), m);
            boolean hasElse = a.getElseBranch() != null && !a.getElseBranch().isEmpty();
            // typical: the branches are taken equally often, and not taking any branch is one of
            // them
            long ways = hasElse ? 2 : 2;
            Usage typical = yes.typical.plus(no.typical).dividedBy(ways);
            Usage worst = yes.worst.max(no.worst);
            return new Bound(typical, worst, yes.unbounded || no.unbounded);
        }

        Bound loop(LoopStmt l, Mult m) {
            long turns = assume.get("assume.loop.iterations");
            int max = l.getMaxIterations();
            long typical = max > 0 ? Math.min(max, turns) : turns;
            long worst = max > 0 ? max : -1;
            Mult inner = m.times(typical, worst);
            Bound body = block(l.getBody(), inner);
            Bound exhausted = block(l.getOnExhausted(), new Mult(0, m.worst));
            // the body's own totals already carry the multiplier; only add the unbounded mark
            return new Bound(
                    body.typical,
                    body.worst.plus(exhausted.worst),
                    body.unbounded || max <= 0 && !body.worst.isEmpty() || exhausted.unbounded);
        }

        Bound forEach(ForEachStmt f, Mult m) {
            long items = assume.get("assume.foreach.items");
            long max = assume.get("assume.foreach.max");
            Mult inner = m.times(items, max);
            Bound body = block(f.getBody(), inner);
            Bound exhausted = block(f.getOnExhausted(), new Mult(0, m.worst));
            return new Bound(
                    body.typical,
                    body.worst.plus(exhausted.worst),
                    body.unbounded || exhausted.unbounded);
        }

        Bound call(CallStmt c, Mult m) {
            WorkflowDef target = workflows.get(c.getWorkflowName());
            if (target == null) return Bound.NONE;
            if (!stack.add(target.getName())) {
                notes.add(
                        "workflow "
                                + target.getName()
                                + " calls itself; the recursion is counted once");
                return Bound.NONE;
            }
            try {
                return block(target.getStatements(), m);
            } finally {
                stack.remove(target.getName());
            }
        }

        /** A model step: records it and returns its cost for the block totals. */
        Bound step(int line, String label, String agentName, String payload, Mult m, int retries) {
            AgentDef agent = agentName == null ? null : agents.get(agentName);
            if (agent == null) {
                Usage dearest = dearestAgentCall(payload);
                if (dearest.isEmpty()) {
                    notes.add(label + " names no agent that is defined, so it is not costed");
                    return Bound.NONE;
                }
                notes.add(
                        label
                                + " has a target known only at run time; it is costed as the dearest agent");
                String model = dearest.byModel().keySet().iterator().next();
                record(line, label, model, dearest, dearest, m, retries);
                return bound(dearest, dearest, m, retries);
            }
            Usage each = callOf(agent, payload, false);
            Usage eachWorst = callOf(agent, payload, true);
            record(line, label, agent.getModel(), each, eachWorst, m, retries);
            return bound(each, eachWorst, m, retries);
        }

        void record(
                int line,
                String label,
                String model,
                Usage each,
                Usage eachWorst,
                Mult m,
                int retries) {
            steps.add(
                    new StepCost(
                            current,
                            line,
                            label,
                            model,
                            each,
                            eachWorst,
                            m.typical,
                            m.worst < 0 ? -1 : m.worst * (retries + 1L)));
        }

        Bound bound(Usage each, Usage eachWorst, Mult m, int retries) {
            boolean boundless = m.worst < 0;
            Usage worst = boundless ? eachWorst : eachWorst.times(m.worst * (retries + 1L));
            return new Bound(each.times(m.typical), worst, boundless);
        }

        Usage dearestAgentCall(String payload) {
            Usage best = Usage.NONE;
            for (AgentDef a : agents.values()) best = best.max(callOf(a, payload, true));
            return best;
        }

        /** One run of an agent on a payload, including the rounds of an agent that uses tools. */
        Usage callOf(AgentDef a, String payload, boolean worst) {
            String model = a.getModel() == null ? "(no model)" : a.getModel();
            long base = systemTokens(a) + payloadTokens(payload);
            long out = assume.get("assume.output.tokens");
            boolean tools = !a.getTools().isEmpty() || !a.getMcpServers().isEmpty();
            if (!tools) return Usage.call(model, base, out);
            long rounds =
                    worst
                            ? (a.getMaxIterations() != null
                                    ? a.getMaxIterations()
                                    : assume.get("assume.react.max"))
                            : assume.get("assume.react.rounds");
            long grow = out + assume.get("assume.tool.result.tokens");
            long in = rounds * base + grow * rounds * (rounds - 1) / 2;
            return Usage.calls(model, rounds, in, rounds * out);
        }

        long systemTokens(AgentDef a) {
            if (a.getSystemPrompt() != null && !a.getSystemPrompt().isBlank())
                return Tokens.of(a.getSystemPrompt());
            if (a.getPrompt() != null && !a.getPrompt().isBlank() && prompts != null) {
                var r = prompts.resolve(a.getPrompt());
                if (r.isPresent()) return Tokens.of(r.get().text());
            }
            if (a.getPrompt() != null && !a.getPrompt().isBlank())
                return assume.get("assume.prompt.tokens");
            return 0;
        }

        long payloadTokens(String payload) {
            if (payload == null) return 0;
            long vars = 0;
            long chars = 0;
            int i = 0;
            while (i < payload.length()) {
                int open = payload.indexOf('{', i);
                int close = open < 0 ? -1 : payload.indexOf('}', open);
                if (open < 0 || close < 0) {
                    chars += payload.length() - i;
                    break;
                }
                chars += open - i;
                vars++;
                i = close + 1;
            }
            return Tokens.ofChars(chars) + vars * assume.get("assume.variable.tokens");
        }
    }
}
