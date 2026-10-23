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
import io.github.llm4j.loom.ast.RewindStmt;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.WorkflowDef;
import io.github.llm4j.loom.prompt.PromptCatalog;
import io.github.llm4j.loom.prompt.PromptSupport;
import io.github.llm4j.loom.review.engine.ReviewInput;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Estimates what each workflow of a script spends, from the script alone. It never calls a model
 * and never counts tokens with a provider, so the same inputs always give the same figures.
 *
 * <p>The workflow is walked in the order it runs, twice. The <b>typical</b> walk uses the
 * assumptions (a loop turns three times, a {@code for each} walks five items, one attempt per step,
 * a short answer). The <b>worst</b> walk uses the most the script allows (a loop's {@code max},
 * every retry, the larger {@code for each}, a long answer); where the script allows no most, the
 * workflow is marked unbounded and no made-up number is given.
 *
 * <p>The walk keeps the <b>shared transcript</b>. Loom's default memory writes every delegate's
 * task and answer into one transcript and puts all of it in front of the next delegate, so a step's
 * input grows with every step before it and a loop costs more each round. The transcript stops
 * growing only where an agent bounds it with {@code memory { max_tokens }} or a {@code context}
 * block. Leaving this out understates a loop several times over (measured against a real provider,
 * see VERIFICATION.md).
 */
public final class CostEstimator {

    /** How many turns of a loop are walked one by one; the rest are added by their trend. */
    private static final int WALK_CAP = 200;

    /** Tokens of the words around a transcript entry ("Agent [A] Task: … Response: …"). */
    private static final long ENTRY_OVERHEAD = 20;

    /** Tokens of "Conversation History:" and "Current Task:" when there is a transcript. */
    private static final long FRAME_OVERHEAD = 8;

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
        Context ctx = new Context(script, input, assume);
        List<WorkflowCost> out = new ArrayList<>();
        for (WorkflowDef w : script.getWorkflows()) out.add(ctx.workflow(w));
        var budget = script.getBudget();
        return new CostEstimate(
                out,
                assume.used(),
                prices,
                priceLabel,
                budget == null ? null : budget.getCost(),
                budget == null ? null : budget.getTokens());
    }

    /** What is the same for both walks of every workflow of one script. */
    private static final class Context {
        final LoomScript script;
        final Assumptions assume;
        final PromptCatalog prompts;
        final Map<String, AgentDef> agents;
        final Map<String, IntentClassifierDef> classifiers;
        final Map<String, WorkflowDef> workflows;
        final long capMin; // smallest transcript bound an agent declares, or -1
        final long capMax; // largest, or -1
        final boolean contextBounded;

        Context(LoomScript script, ReviewInput input, Assumptions assume) {
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
            long min = -1;
            long max = -1;
            boolean ctx = false;
            for (AgentDef a : script.getAgents()) {
                if (a.getMemory() != null && a.getMemory().getMaxTokens() != null) {
                    long m = a.getMemory().getMaxTokens();
                    min = min < 0 ? m : Math.min(min, m);
                    max = Math.max(max, m);
                }
                ctx |= a.getContext() != null;
            }
            this.capMin = min;
            this.capMax = max;
            this.contextBounded = ctx;
        }

        WorkflowCost workflow(WorkflowDef w) {
            Walk typical = new Walk(this, false, w.getName());
            Walk worst = new Walk(this, true, w.getName());
            typical.run(w);
            worst.run(w);
            long rewinds = rewindBudget(w.getStatements());
            Usage worstUsage = worst.total.times(1 + rewinds);
            List<String> notes = new ArrayList<>(typical.notes);
            if (rewinds > 0)
                notes.add(
                        "rewind may re-run work up to "
                                + rewinds
                                + " times; the worst case allows for it by repeating the whole workflow that many times more");
            List<StepCost> steps = new ArrayList<>();
            for (var e : typical.steps.entrySet()) {
                Acc t = e.getValue();
                Acc wc = worst.steps.get(e.getKey());
                steps.add(
                        new StepCost(
                                w.getName(),
                                t.line,
                                t.label,
                                t.model,
                                t.usage,
                                wc == null ? t.usage : wc.usage,
                                t.retries,
                                t.runs,
                                wc == null ? t.runs : wc.unbounded ? -1 : wc.runs));
            }
            return new WorkflowCost(
                    w.getName(), typical.total, worstUsage, worst.unbounded, steps, notes);
        }

        long rewindBudget(List<Statement> list) {
            long[] sum = {0};
            StatementWalker.walk(
                    list,
                    s -> {
                        if (s instanceof RewindStmt r) sum[0] += Math.max(r.getAtMost(), 0);
                    });
            return sum[0];
        }
    }

    /** What one step has spent so far in one walk. */
    private static final class Acc {
        int line;
        String label;
        String model;
        int retries;
        Usage usage = Usage.NONE;
        long runs;
        boolean unbounded;
    }

    /** One walk of one workflow, in the order it runs, keeping the shared transcript. */
    private static final class Walk {
        final Context c;
        final boolean worst;
        final Set<String> stack = new HashSet<>();
        final Map<String, Acc> steps = new LinkedHashMap<>();
        final List<String> notes = new ArrayList<>();
        Usage total = Usage.NONE;
        long transcript;
        boolean unbounded;
        int boundless; // depth of loops with no bound we are inside
        final Set<String> answers = new HashSet<>(); // variables holding a model's answer
        long weight = 1; // typical walk: an alt halves what its branches contribute

        Walk(Context c, boolean worst, String name) {
            this.c = c;
            this.worst = worst;
            stack.add(name);
        }

        void run(WorkflowDef w) {
            block(w.getStatements());
        }

        void block(List<Statement> list) {
            for (Statement s : list == null ? List.<Statement>of() : list) statement(s);
        }

        void statement(Statement s) {
            dispatch(s);
            if (s instanceof DelegateStmt d && d.getVariableName() != null)
                answers.add(d.getVariableName());
            else if (s instanceof BroadcastStmt b && b.getVariableName() != null)
                answers.add(b.getVariableName());
        }

        void dispatch(Statement s) {
            if (s instanceof DelegateStmt d) delegate(d);
            else if (s instanceof BroadcastStmt b)
                together(
                        b.getTargetAgents().stream()
                                .<Runnable>map(
                                        a ->
                                                () ->
                                                        agentStep(
                                                                b.getLine(),
                                                                "broadcast " + a,
                                                                a,
                                                                b.getPayload(),
                                                                0))
                                .toList());
            else if (s instanceof HandoffStmt h)
                agentStep(
                        h.getLine(),
                        "handoff " + h.getTargetAgent(),
                        h.getTargetAgent(),
                        h.getPayload(),
                        0);
            else if (s instanceof IntentClassifyStmt k) classify(k);
            else if (s instanceof AltStmt a) alt(a);
            else if (s instanceof LoopStmt l) loop(l);
            else if (s instanceof ForEachStmt f) forEach(f);
            else if (s instanceof io.github.llm4j.loom.ast.ParallelStmt p)
                together(p.getBody().stream().<Runnable>map(x -> () -> statement(x)).toList());
            else if (s instanceof GuardrailStmt g) {
                block(g.getBody());
                if (worst) block(g.getOnViolation());
            } else if (s instanceof CallStmt k) call(k);
        }

        /**
         * Branches that run at the same time. Typically they all start before any has written to
         * the shared transcript, so each sees only what was there before; at worst they run one
         * after another and each sees the ones before it. Afterwards the transcript holds all of
         * them either way.
         */
        void together(List<Runnable> branches) {
            if (worst) {
                branches.forEach(Runnable::run);
                return;
            }
            long start = transcript;
            long added = 0;
            for (Runnable branch : branches) {
                transcript = start;
                branch.run();
                added += transcript - start;
            }
            transcript = start + added;
            long cap = cap();
            if (cap >= 0 && transcript > cap) transcript = cap;
        }

        void delegate(DelegateStmt d) {
            String label = "delegate " + (d.hasDynamicTarget() ? "(dynamic)" : d.getTargetAgent());
            agentStep(d.getLine(), label, d.getTargetAgent(), d.getPayload(), d.getRetryCount());
            if (worst) block(d.getOnFailure());
        }

        void classify(IntentClassifyStmt k) {
            IntentClassifierDef def = c.classifiers.get(k.getClassifier());
            if (def == null || def.getModel() == null) {
                note("classifier " + k.getClassifier() + " has no model to price");
                return;
            }
            long in =
                    k.getInputs().size()
                                    * c.assume.get(
                                            worst
                                                    ? "assume.variable.max"
                                                    : "assume.variable.tokens")
                            + 30;
            int samples = Math.max(1, def.getSamples());
            Usage each = Usage.calls(def.getModel(), samples, in * samples, 10L * samples);
            long attempts = worst ? k.getRetryCount() + 1L : 1;
            record(
                    k.getLine(),
                    "intentclassify " + k.getClassifier(),
                    def.getModel(),
                    each.times(attempts),
                    k.getRetryCount());
        }

        void alt(AltStmt a) {
            if (worst) {
                State before = save();
                block(a.getIfBranch());
                State yes = save();
                restore(before);
                block(a.getElseBranch());
                State no = save();
                // the dearer way, and the longer transcript of either
                restore(yes.total.tokens() >= no.total.tokens() ? yes : no);
                transcript = Math.max(yes.transcript, no.transcript);
                unbounded = yes.unbounded || no.unbounded;
                return;
            }
            // typical: the two ways are equally likely, so each contributes half
            long before = weight;
            weight = before * 2;
            block(a.getIfBranch());
            block(a.getElseBranch());
            weight = before;
        }

        void loop(LoopStmt l) {
            long turns = c.assume.get("assume.loop.iterations");
            int max = l.getMaxIterations();
            long n;
            if (worst) {
                if (max > 0) n = max;
                else {
                    n = 1; // at least one turn; the rest has no bound
                    boundless++;
                }
            } else n = max > 0 ? Math.min(max, turns) : turns;
            long spentBefore = total.tokens();
            repeat(n, () -> block(l.getBody()));
            if (worst && max <= 0) {
                boundless--;
                // a loop whose body calls no model costs nothing however long it turns
                if (total.tokens() > spentBefore) unbounded = true;
            }
            if (worst) block(l.getOnExhausted());
        }

        void forEach(ForEachStmt f) {
            long n = c.assume.get(worst ? "assume.foreach.max" : "assume.foreach.items");
            repeat(n, () -> block(f.getBody()));
            if (worst) block(f.getOnExhausted());
        }

        /**
         * Runs a body {@code n} times. The first turns are walked one by one because each costs
         * more than the one before (the transcript grows); beyond {@link #WALK_CAP} turns the
         * remaining ones are added by the trend of the last two.
         */
        void repeat(long n, Runnable body) {
            long walked = Math.min(n, WALK_CAP);
            Map<String, Long> runsBefore = new LinkedHashMap<>();
            steps.forEach((k, a) -> runsBefore.put(k, a.runs));
            Usage previous = Usage.NONE;
            Usage last = Usage.NONE;
            for (long i = 0; i < walked; i++) {
                Usage before = total;
                body.run();
                previous = last;
                last = diff(total, before);
            }
            long rest = n - walked;
            if (rest > 0 && !last.isEmpty()) {
                Usage grow = diff(last, previous);
                Usage extra = last.times(rest);
                if (!grow.isEmpty()) extra = extra.plus(grow.times(rest * (rest + 1) / 2));
                total = total.plus(extra.dividedBy(weight));
                for (var e : steps.entrySet()) {
                    long delta = e.getValue().runs - runsBefore.getOrDefault(e.getKey(), 0L);
                    e.getValue().runs += delta / walked * rest;
                }
                note(
                        "a loop of more than "
                                + WALK_CAP
                                + " turns is walked for "
                                + WALK_CAP
                                + " and the rest added by its trend; per-step figures cover the first "
                                + WALK_CAP);
            }
        }

        void call(CallStmt k) {
            WorkflowDef target = c.workflows.get(k.getWorkflowName());
            if (target == null) return;
            if (!stack.add(target.getName())) {
                note(
                        "workflow "
                                + target.getName()
                                + " calls itself; the recursion is counted once");
                return;
            }
            try {
                block(target.getStatements());
            } finally {
                stack.remove(target.getName());
            }
        }

        // ---- one model step

        void agentStep(int line, String label, String agentName, String payload, int retries) {
            AgentDef agent = agentName == null ? null : c.agents.get(agentName);
            Usage one;
            String model;
            if (agent == null) {
                AgentDef dearest = dearest(payload);
                if (dearest == null) {
                    note(label + " names no agent that is defined, so it is not costed");
                    return;
                }
                note(
                        label
                                + " has a target known only at run time; it is costed as the dearest agent");
                agent = dearest;
            }
            model = agent.getModel() == null ? "(no model)" : agent.getModel();
            one = callOf(agent, payload);
            long attempts = worst ? retries + 1L : 1;
            record(line, label, model, one.times(attempts), retries);
            long answer = answerTokens();
            addToTranscript(payloadTokens(payload) + answer + ENTRY_OVERHEAD);
        }

        AgentDef dearest(String payload) {
            AgentDef best = null;
            long bestTokens = -1;
            for (AgentDef a : c.agents.values()) {
                long t = callOf(a, payload).tokens();
                if (t > bestTokens) {
                    best = a;
                    bestTokens = t;
                }
            }
            return best;
        }

        /**
         * One run of an agent on a payload, including the transcript in front of it and any tool
         * rounds.
         */
        Usage callOf(AgentDef a, String payload) {
            String model = a.getModel() == null ? "(no model)" : a.getModel();
            long history = transcript > 0 ? transcript + FRAME_OVERHEAD : 0;
            long base = systemTokens(a) + payloadTokens(payload) + history;
            long out = answerTokens();
            boolean tools = !a.getTools().isEmpty() || !a.getMcpServers().isEmpty();
            if (!tools) return Usage.call(model, base, out);
            long rounds =
                    worst
                            ? (a.getMaxIterations() != null
                                    ? a.getMaxIterations()
                                    : c.assume.get("assume.react.max"))
                            : c.assume.get("assume.react.rounds");
            long grow = out + c.assume.get("assume.tool.result.tokens");
            long in = rounds * base + grow * rounds * (rounds - 1) / 2;
            return Usage.calls(model, rounds, in, rounds * out);
        }

        long answerTokens() {
            return c.assume.get(worst ? "assume.output.max" : "assume.output.tokens");
        }

        void addToTranscript(long tokens) {
            long add = Math.max(1, tokens / weight);
            transcript += add;
            long cap = cap();
            if (cap >= 0 && transcript > cap) transcript = cap;
        }

        /** The most the shared transcript can hold, or -1 when nothing bounds it. */
        long cap() {
            if (worst ? c.capMax >= 0 : c.capMin >= 0) return worst ? c.capMax : c.capMin;
            if (c.contextBounded) return c.assume.get("assume.transcript.cap");
            return -1;
        }

        void record(int line, String label, String model, Usage usage, int retries) {
            Usage counted = usage.dividedBy(weight);
            total = total.plus(counted);
            String key = line + ":" + label;
            Acc a = steps.computeIfAbsent(key, k -> new Acc());
            a.line = line;
            a.label = label;
            a.model = model;
            a.retries = retries;
            a.usage = a.usage.plus(counted);
            a.runs += 1;
            if (boundless > 0) a.unbounded = true;
        }

        long systemTokens(AgentDef a) {
            if (a.getSystemPrompt() != null && !a.getSystemPrompt().isBlank())
                return Tokens.of(a.getSystemPrompt());
            if (a.getPrompt() != null && !a.getPrompt().isBlank() && c.prompts != null) {
                var r = c.prompts.resolve(a.getPrompt());
                if (r.isPresent()) return Tokens.of(r.get().text());
            }
            if (a.getPrompt() != null && !a.getPrompt().isBlank())
                return c.assume.get("assume.prompt.tokens");
            return 0;
        }

        /**
         * Tokens of a prompt: the text the script wrote, plus each {@code {variable}} filled in. A
         * variable that holds a model's answer is as long as an answer; any other (an input of the
         * workflow) is {@code assume.variable.tokens} long.
         */
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
                String name = payload.substring(open + 1, close);
                int dot = name.indexOf('.');
                String base = dot < 0 ? name : name.substring(0, dot);
                vars +=
                        answers.contains(base)
                                ? answerTokens()
                                : c.assume.get(
                                        worst ? "assume.variable.max" : "assume.variable.tokens");
                i = close + 1;
            }
            return Tokens.ofChars(chars) + vars;
        }

        void note(String text) {
            if (!notes.contains(text)) notes.add(text);
        }

        // ---- saving and restoring the totals around the two ways of an alt (worst walk)

        record State(Usage total, long transcript, boolean unbounded) {}

        State save() {
            return new State(total, transcript, unbounded);
        }

        void restore(State s) {
            total = s.total();
            transcript = s.transcript();
            unbounded = s.unbounded();
        }

        static Usage diff(Usage a, Usage b) {
            return a.minus(b);
        }
    }
}
