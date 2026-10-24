package io.github.llm4j.loom.review.cost;

import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.AltStmt;
import io.github.llm4j.loom.ast.BroadcastStmt;
import io.github.llm4j.loom.ast.CallStmt;
import io.github.llm4j.loom.ast.DecideStmt;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.ForEachStmt;
import io.github.llm4j.loom.ast.GuardrailStmt;
import io.github.llm4j.loom.ast.HandoffStmt;
import io.github.llm4j.loom.ast.HumanPromptStmt;
import io.github.llm4j.loom.ast.IntentClassifierDef;
import io.github.llm4j.loom.ast.IntentClassifyStmt;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.LoopStmt;
import io.github.llm4j.loom.ast.ParallelStmt;
import io.github.llm4j.loom.ast.RewindStmt;
import io.github.llm4j.loom.ast.RunStmt;
import io.github.llm4j.loom.ast.SleepStmt;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.ToolDef;
import io.github.llm4j.loom.ast.WorkflowDef;
import io.github.llm4j.loom.review.engine.ReviewInput;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import io.github.llm4j.loom.security.Capabilities;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Estimates how long each workflow takes and how many effects it can cause, from the script alone.
 * It walks the workflow in the order it runs, twice (typical, worst), as {@link CostEstimator}
 * does, but does not need the transcript: time and effects depend on the shape of the workflow
 * (what is sequential, what is parallel, what repeats), not on how many tokens are in a prompt.
 *
 * <p>Sequential statements add. {@code parallel}, {@code broadcast} and {@code parallel for each}
 * take the longest branch. Loops and {@code for each} multiply as in the cost estimate; a loop with
 * no bound that takes time (or causes an effect) makes the worst case unbounded, and no number is
 * made up. A retry adds its backoff and repeats its effects. Waiting for a person or a timer is
 * listed, not added.
 */
public final class RunProfile {

    private final ReviewInput input;
    private final Assumptions assume;
    private final Map<String, AgentDef> agents;
    private final Map<String, IntentClassifierDef> classifiers;
    private final Map<String, WorkflowDef> workflows;
    private final Map<String, ToolDef> tools = new LinkedHashMap<>();

    private RunProfile(ReviewInput input, ReviewSettings settings) {
        this.input = input;
        this.assume = Assumptions.of(settings);
        LoomScript script = input.script();
        agents =
                script.getAgents().stream()
                        .collect(Collectors.toMap(AgentDef::getName, a -> a, (a, b) -> a));
        classifiers =
                script.getIntentClassifiers().stream()
                        .collect(
                                Collectors.toMap(
                                        IntentClassifierDef::getName, c -> c, (a, b) -> a));
        workflows =
                script.getWorkflows().stream()
                        .collect(Collectors.toMap(WorkflowDef::getName, w -> w, (a, b) -> a));
        for (ToolDef t : script.getTools()) tools.put(t.getName(), t);
    }

    /** The time and effects of every workflow of the script, by workflow name. */
    public static Map<String, Profile> of(ReviewInput input, ReviewSettings settings) {
        RunProfile p = new RunProfile(input, settings);
        Map<String, Profile> out = new LinkedHashMap<>();
        for (WorkflowDef w : input.script().getWorkflows()) out.put(w.getName(), p.workflow(w));
        return out;
    }

    /** What is known about one workflow. */
    public record Profile(TimeEstimate time, EffectsEstimate effects) {}

    private Profile workflow(WorkflowDef w) {
        Walk typical = new Walk(false, w.getName());
        Walk worst = new Walk(true, w.getName());
        Part t = typical.block(w.getStatements());
        Part x = worst.block(w.getStatements());
        long rewinds = rewindBudget(w.getStatements());
        double repeat = 1 + rewinds;
        List<String> waits = new ArrayList<>(typical.waits);
        for (String s : worst.waits) if (!waits.contains(s)) waits.add(s);
        long sleep = 0;
        for (Part p : List.of(t)) sleep += (long) p.sleep;
        TimeEstimate time =
                new TimeEstimate(
                        Math.round(t.ms),
                        Math.round(x.ms * repeat),
                        x.unboundedTime,
                        x.path,
                        waits,
                        sleep,
                        assume.used(),
                        Math.round(x.questions * repeat),
                        x.unboundedQuestions);
        EffectsEstimate effects =
                new EffectsEstimate(
                        new EffectsEstimate.Count(
                                Math.round(t.sends),
                                Math.round(x.sends * repeat),
                                x.unboundedSends),
                        new EffectsEstimate.Count(
                                Math.round(t.writes),
                                Math.round(x.writes * repeat),
                                x.unboundedWrites));
        return new Profile(time, effects);
    }

    private static long rewindBudget(List<Statement> list) {
        long[] sum = {0};
        StatementWalker.walk(
                list,
                s -> {
                    if (s instanceof RewindStmt r) sum[0] += Math.max(r.getAtMost(), 0);
                });
        return sum[0];
    }

    // ---- model tiers

    /** The latency class of a model, from its name: small, standard or large. */
    static String tier(String model) {
        String m = model == null ? "" : model.toLowerCase(Locale.ROOT);
        for (String s : List.of("haiku", "mini", "nano", "flash", "small", "lite", "8b"))
            if (m.contains(s)) return "small";
        for (String s : List.of("opus", "large", "fable", "ultra", "o1", "o3", "-pro", "gpt-5"))
            if (m.contains(s)) return "large";
        return "standard";
    }

    /** The assumed time of one model call, for rules that compare two steps. */
    public static long latencyMillis(String model, ReviewSettings settings) {
        return Assumptions.of(settings).get("assume.latency." + tier(model)) * 1000;
    }

    private long callMillis(String model) {
        return assume.get("assume.latency." + tier(model)) * 1000;
    }

    // ---- what a part of a workflow takes and does

    /** The time and effects of a stretch of workflow, and the steps its time is spent on. */
    private static final class Part {
        double ms;
        double sends;
        double writes;
        double sleep;
        double questions;
        boolean unboundedQuestions;
        boolean unboundedTime;
        boolean unboundedSends;
        boolean unboundedWrites;
        List<String> path = List.of();

        static final Part NONE = new Part();

        /** This, then {@code next}. */
        Part then(Part next) {
            Part p = new Part();
            p.ms = ms + next.ms;
            p.sends = sends + next.sends;
            p.writes = writes + next.writes;
            p.sleep = sleep + next.sleep;
            p.questions = questions + next.questions;
            p.unboundedQuestions = unboundedQuestions || next.unboundedQuestions;
            p.unboundedTime = unboundedTime || next.unboundedTime;
            p.unboundedSends = unboundedSends || next.unboundedSends;
            p.unboundedWrites = unboundedWrites || next.unboundedWrites;
            p.path = concat(path, next.path);
            return p;
        }

        /** Both at the same time: the longer takes the time, and the effects of both happen. */
        Part alongside(Part other) {
            Part p = then(other);
            Part longer = ms >= other.ms ? this : other;
            p.ms = longer.ms;
            p.path = longer.path;
            return p;
        }

        /** One of the two: the dearer of each measure. */
        Part orElse(Part other) {
            Part p = new Part();
            p.ms = Math.max(ms, other.ms);
            p.sends = Math.max(sends, other.sends);
            p.writes = Math.max(writes, other.writes);
            p.sleep = Math.max(sleep, other.sleep);
            p.questions = Math.max(questions, other.questions);
            p.unboundedQuestions = unboundedQuestions || other.unboundedQuestions;
            p.unboundedTime = unboundedTime || other.unboundedTime;
            p.unboundedSends = unboundedSends || other.unboundedSends;
            p.unboundedWrites = unboundedWrites || other.unboundedWrites;
            p.path = ms >= other.ms ? path : other.path;
            return p;
        }

        /** The average of the two (an alt, typically). */
        Part meanWith(Part other) {
            Part p = new Part();
            p.ms = (ms + other.ms) / 2;
            p.sends = (sends + other.sends) / 2;
            p.writes = (writes + other.writes) / 2;
            p.sleep = (sleep + other.sleep) / 2;
            p.questions = (questions + other.questions) / 2;
            p.path = ms >= other.ms ? path : other.path;
            return p;
        }

        /** {@code n} times in a row. */
        Part times(double n) {
            Part p = new Part();
            p.ms = ms * n;
            p.sends = sends * n;
            p.writes = writes * n;
            p.sleep = sleep * n;
            p.questions = questions * n;
            p.unboundedQuestions = unboundedQuestions;
            p.unboundedTime = unboundedTime;
            p.unboundedSends = unboundedSends;
            p.unboundedWrites = unboundedWrites;
            p.path = path;
            return p;
        }

        static List<String> concat(List<String> a, List<String> b) {
            if (a.isEmpty()) return b;
            if (b.isEmpty()) return a;
            List<String> out = new ArrayList<>(a);
            out.addAll(b);
            return out;
        }
    }

    // ---- one walk

    private final class Walk {
        final boolean worst;
        final Set<String> stack = new HashSet<>();
        final List<String> waits = new ArrayList<>();

        Walk(boolean worst, String name) {
            this.worst = worst;
            stack.add(name);
        }

        Part block(List<Statement> list) {
            Part p = Part.NONE;
            for (Statement s : list == null ? List.<Statement>of() : list) p = p.then(statement(s));
            return p;
        }

        Part statement(Statement s) {
            if (s instanceof DelegateStmt d) {
                Part step =
                        agentStep(
                                d.getLine(),
                                "delegate "
                                        + (d.hasDynamicTarget() ? "(dynamic)" : d.getTargetAgent()),
                                d.getTargetAgent(),
                                d.getRetryCount(),
                                d.getBackoffMillis(),
                                d.getTimeoutMillis());
                return worst ? step.then(block(d.getOnFailure())) : step;
            }
            if (s instanceof HandoffStmt h)
                return agentStep(
                        h.getLine(), "handoff " + h.getTargetAgent(), h.getTargetAgent(), 0, 0, 0);
            if (s instanceof BroadcastStmt b) {
                Part all = Part.NONE;
                for (String a : b.getTargetAgents())
                    all = all.alongside(agentStep(b.getLine(), "broadcast " + a, a, 0, 0, 0));
                return all;
            }
            if (s instanceof ParallelStmt p) {
                Part all = Part.NONE;
                for (Statement x : p.getBody()) all = all.alongside(statement(x));
                return all;
            }
            if (s instanceof IntentClassifyStmt k) return classify(k);
            if (s instanceof RunStmt r) return task(r);
            if (s instanceof AltStmt a) return alt(a);
            if (s instanceof LoopStmt l) return loop(l);
            if (s instanceof ForEachStmt f) return forEach(f);
            if (s instanceof GuardrailStmt g)
                return worst
                        ? block(g.getBody()).then(block(g.getOnViolation()))
                        : block(g.getBody());
            if (s instanceof CallStmt k) return call(k);
            if (s instanceof HumanPromptStmt h) {
                wait("human_prompt (line " + h.getLine() + ")");
                Part p = new Part();
                p.questions = 1;
                return p;
            }
            if (s instanceof DecideStmt d) {
                Part p = new Part();
                p.questions = 1;
                return p;
            }
            if (s instanceof SleepStmt z) {
                wait("sleep " + z.getDuration() + " (line " + z.getLine() + ")");
                Part p = new Part();
                p.sleep = z.getDuration().toMillis();
                return p;
            }
            return Part.NONE;
        }

        void wait(String what) {
            if (!waits.contains(what)) waits.add(what);
        }

        Part alt(AltStmt a) {
            Part yes = block(a.getIfBranch());
            Part no = block(a.getElseBranch());
            return worst ? yes.orElse(no) : yes.meanWith(no);
        }

        Part loop(LoopStmt l) {
            int max = l.getMaxIterations();
            Part body = block(l.getBody());
            if (!worst) {
                long turns = assume.get("assume.loop.iterations");
                return body.times(max > 0 ? Math.min(max, turns) : turns);
            }
            Part all;
            if (max > 0) all = body.times(max);
            else {
                all = body.times(1);
                if (body.ms > 0) all.unboundedTime = true;
                if (body.questions > 0) all.unboundedQuestions = true;
                if (body.sends > 0) all.unboundedSends = true;
                if (body.writes > 0) all.unboundedWrites = true;
            }
            return all.then(block(l.getOnExhausted()));
        }

        Part forEach(ForEachStmt f) {
            long n = assume.get(worst ? "assume.foreach.max" : "assume.foreach.items");
            Part body = block(f.getBody());
            // parallel: the items overlap in time, but every one still causes its effects
            Part all = body.times(n);
            if (f.isParallel()) all.ms = body.ms;
            return worst ? all.then(block(f.getOnExhausted())) : all;
        }

        Part call(CallStmt k) {
            WorkflowDef target = workflows.get(k.getWorkflowName());
            if (target == null || !stack.add(target.getName())) return Part.NONE;
            try {
                return block(target.getStatements());
            } finally {
                stack.remove(target.getName());
            }
        }

        Part task(RunStmt r) {
            Part p = new Part();
            long attempts = worst ? r.getRetryCount() + 1L : 1;
            long one = assume.get("assume.latency.tool") * 1000;
            if (r.getTimeoutMillis() > 0) one = Math.min(one, r.getTimeoutMillis());
            p.ms = one * attempts + (worst ? r.getRetryCount() * (double) r.getBackoffMillis() : 0);
            p.path = List.of("line " + r.getLine() + " run " + r.getTaskName());
            return p;
        }

        Part classify(IntentClassifyStmt k) {
            IntentClassifierDef def = classifiers.get(k.getClassifier());
            if (def == null || def.getModel() == null) return Part.NONE;
            long attempts = worst ? k.getRetryCount() + 1L : 1;
            long one = callMillis(def.getModel()) * Math.max(1, def.getSamples());
            if (k.getTimeoutMillis() > 0) one = Math.min(one, k.getTimeoutMillis());
            Part p = new Part();
            p.ms = one * attempts + (worst ? k.getRetryCount() * (double) k.getBackoffMillis() : 0);
            p.path = List.of("line " + k.getLine() + " intentclassify " + k.getClassifier());
            return p;
        }

        Part agentStep(
                int line, String label, String agentName, int retries, long backoff, long timeout) {
            AgentDef agent = agentName == null ? null : agents.get(agentName);
            if (agent == null) agent = slowest();
            if (agent == null) return Part.NONE;
            boolean tools = !agent.getTools().isEmpty() || !agent.getMcpServers().isEmpty();
            long model = callMillis(agent.getModel());
            long rounds = 1;
            if (tools)
                rounds =
                        worst
                                ? (agent.getMaxIterations() != null
                                        ? agent.getMaxIterations()
                                        : assume.get("assume.react.max"))
                                : assume.get("assume.react.rounds");
            double one =
                    rounds * model
                            + (tools ? rounds * assume.get("assume.latency.tool") * 1000 : 0);
            if (timeout > 0) one = Math.min(one, timeout);
            double attempts = worst ? retries + 1.0 : 1.0;
            Part p = new Part();
            p.ms = one * attempts + (worst ? retries * (double) backoff : 0);
            p.path = List.of("line " + line + " " + label);
            if (tools && (agent.isApproveAll() || !agent.getApprove().isEmpty()))
                wait("approval of a tool call by " + agent.getName() + " (line " + line + ")");
            double calls = worst ? rounds : assume.get("assume.effects.calls");
            if (holds(agent, "sends")) p.sends = calls * attempts;
            if (holds(agent, "writes")) p.writes = calls * attempts;
            return p;
        }

        AgentDef slowest() {
            AgentDef best = null;
            long ms = -1;
            for (AgentDef a : agents.values()) {
                long t = callMillis(a.getModel()) * (a.getTools().isEmpty() ? 1 : 3);
                if (t > ms) {
                    best = a;
                    ms = t;
                }
            }
            return best;
        }
    }

    /** True when the agent holds a tool whose reach is exactly {@code kind} (sends or writes). */
    private boolean holds(AgentDef agent, String kind) {
        return effectKinds(agent, input).contains(kind);
    }

    /**
     * What the agent's tools can do to the world: {@code sends} for one that sends anything out or
     * whose reach nobody declared, {@code writes} for one that changes something.
     */
    public static Set<String> effectKinds(AgentDef agent, ReviewInput input) {
        Map<String, ToolDef> declared = new LinkedHashMap<>();
        for (ToolDef t : input.script().getTools()) declared.put(t.getName(), t);
        Set<String> kinds = new java.util.TreeSet<>();
        for (String name : agent.getTools()) {
            Capabilities c = Capabilities.resolve(name, declared, input.lootReach());
            if (!c.known() || c.outward()) kinds.add("sends");
            else if (c.effect()) kinds.add("writes");
        }
        return kinds;
    }
}
