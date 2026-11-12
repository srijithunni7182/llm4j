package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.AltStmt;
import io.github.llm4j.loom.ast.BroadcastStmt;
import io.github.llm4j.loom.ast.CallStmt;
import io.github.llm4j.loom.ast.CancelStmt;
import io.github.llm4j.loom.ast.CheckpointStmt;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.ForEachStmt;
import io.github.llm4j.loom.ast.HandleRef;
import io.github.llm4j.loom.ast.HandoffStmt;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.LoopStmt;
import io.github.llm4j.loom.ast.ParallelStmt;
import io.github.llm4j.loom.ast.ReturnStmt;
import io.github.llm4j.loom.ast.RunStmt;
import io.github.llm4j.loom.ast.SleepStmt;
import io.github.llm4j.loom.ast.StartStmt;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.SteerStmt;
import io.github.llm4j.loom.ast.SuperviseStmt;
import io.github.llm4j.loom.ast.UseStmt;
import io.github.llm4j.loom.ast.ValueExpr;
import io.github.llm4j.loom.ast.WaitStmt;
import io.github.llm4j.loom.ast.WorkflowDef;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The static checks of {@code start}, {@code wait}, {@code cancel}, {@code steer} and {@code
 * supervise} (phase 33, decisions D2, D8 to D14, D21 to D28): what can be known before a run, with
 * the line and what to write. A handle is a capability held by the script, so most of the rules are
 * about where one may and may not appear.
 */
final class ChildChecks {

    /** The facts of a handle that may be read as plain values (D9, D23). */
    static final Set<String> FACTS =
            Set.of(
                    "status", "result", "partial", "reason", "failure", "tokens", "cost", "calls",
                    "elapsed", "idle", "wakes");

    /** The shortest cadence of a judge, in milliseconds (D26). */
    static final long JUDGE_FLOOR_MILLIS = 30_000;

    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)(\\[[^\\]]*\\])?(\\.[A-Za-z0-9_.]*)?\\}");
    private static final Pattern IS_OK = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)(\\[[^\\]]*\\])? is (?:not )?ok");

    private ChildChecks() {}

    static void run(ScriptValidator.Checker c) {
        LoomScript script = c.script();
        for (WorkflowDef w : script.getWorkflows()) workflow(c, script, w);
    }

    private static void workflow(ScriptValidator.Checker c, LoomScript script, WorkflowDef w) {
        List<StartStmt> starts = new ArrayList<>();
        List<WaitStmt> waits = new ArrayList<>();
        List<CancelStmt> cancels = new ArrayList<>();
        List<SteerStmt> steers = new ArrayList<>();
        List<SuperviseStmt> supervises = new ArrayList<>();
        StatementWalker.walk(
                w.getStatements(),
                s -> {
                    if (s instanceof StartStmt x) starts.add(x);
                    else if (s instanceof WaitStmt x) waits.add(x);
                    else if (s instanceof CancelStmt x) cancels.add(x);
                    else if (s instanceof SteerStmt x) steers.add(x);
                    else if (s instanceof SuperviseStmt x) supervises.add(x);
                });
        Set<String> handles = new LinkedHashSet<>();
        starts.forEach(s -> handles.add(s.getTarget().name()));
        Set<String> known = new HashSet<>(handles);
        known.addAll(w.getParameters()); // a handle may be passed into a call

        for (StartStmt s : starts) start(c, script, w, s);
        for (WaitStmt s : waits) {
            if (s.getTimeoutMillis() == null)
                c.error(
                        s.getLine(),
                        "wait " + s.getTarget().describe(),
                        "a wait needs a timeout, so nothing waits for ever: wait "
                                + s.getTarget().describe()
                                + " timeout 5m -> result");
            isHandle(c, known, s.getTarget(), s.getLine(), "wait");
        }
        for (CancelStmt s : cancels) {
            isHandle(c, known, s.getTarget(), s.getLine(), "cancel");
            if (s.getRewindCheckpoint() != null)
                checkpointExists(c, script, childOf(script, starts, s.getTarget()), s.getRewindCheckpoint(), s.getLine(), "cancel");
        }
        for (SteerStmt s : steers) {
            isHandle(c, known, s.getTarget(), s.getLine(), "steer");
            steerable(c, script, s.getAgent(), s.getLine(), "steer " + s.getTarget().describe());
        }
        Map<String, SuperviseStmt> blocks = new LinkedHashMap<>();
        for (SuperviseStmt s : supervises) {
            isHandle(c, known, s.getTarget(), s.getLine(), "supervise");
            SuperviseStmt earlier = blocks.putIfAbsent(s.getTarget().describe(), s);
            if (earlier != null)
                c.error(
                        s.getLine(),
                        "supervise " + s.getTarget().describe(),
                        "one supervise block for each handle: " + s.getTarget().describe()
                                + " is already supervised on line " + earlier.getLine()
                                + ". Put the rules together in one block.");
            supervise(c, script, starts, s);
        }

        unwaited(c, handles, waits, cancels, supervises, starts);
        handleUse(c, w, handles);
        resultReads(c, w, handles);
    }

    // ---- start ----

    private static void start(
            ScriptValidator.Checker c, LoomScript script, WorkflowDef parent, StartStmt s) {
        String construct = "start " + s.getWorkflow();
        WorkflowDef child = find(script, s.getWorkflow());
        if (child == null) {
            c.error(
                    s.getLine(),
                    construct,
                    "there is no workflow " + s.getWorkflow() + " to start. Declare it, or import the file that does.");
        } else if (child == parent) {
            c.warn(
                    s.getLine(),
                    construct,
                    "workflow " + parent.getName() + " starts itself: each child starts another, so the depth limit (max_depth) is what ends it. Start a different workflow, or make sure that is what you want.");
        }
        if (s.getBudget() == null)
            c.error(
                    s.getLine(),
                    construct,
                    "a start needs a budget for the child, so a runaway child cannot spend the parent's: start "
                            + s.getWorkflow() + "(...) budget { tokens: 100000 } -> " + s.getTarget().describe());
        if (child != null && s.getTimeoutMillis() != null) {
            long sleeps = longestSleepMillis(script, child, new HashSet<>());
            if (sleeps > s.getTimeoutMillis())
                c.warn(
                        s.getLine(),
                        construct,
                        "the child sleeps for longer than its timeout: the deadline of a start is wall clock and counts a sleep, so the child would be timed out while it sleeps. Lengthen the timeout or shorten the sleep.");
        }
    }

    // ---- the loop bound and a handle's use ----

    private static void handleUse(
            ScriptValidator.Checker c, WorkflowDef w, Set<String> handles) {
        unboundedStarts(c, w.getStatements(), false);
        if (handles.isEmpty()) return;
        StatementWalker.walk(
                w.getStatements(),
                s -> {
                    for (SubstitutedText.Entry e : SubstitutedText.entries(s)) {
                        Matcher m = PLACEHOLDER.matcher(e.text());
                        while (m.find()) {
                            if (!handles.contains(m.group(1))) continue;
                            String fact = m.group(3) == null ? null : m.group(3).substring(1);
                            if (fact == null || !FACTS.contains(head(fact)))
                                c.error(
                                        s.getLine(),
                                        e.position(),
                                        "{" + m.group(1) + (m.group(2) == null ? "" : m.group(2))
                                                + (fact == null ? "" : "." + fact)
                                                + "} is a handle, and a handle is never written into text a model could read. Read a fact instead, such as {"
                                                + m.group(1) + ".status}.");
                        }
                    }
                    List<RunStmt.Arg> args =
                            s instanceof RunStmt r ? r.getArgs() : s instanceof UseStmt u ? u.getArgs() : List.of();
                    for (RunStmt.Arg a : args)
                        if (a.kind() == RunStmt.Kind.REFERENCE
                                && handles.contains(baseOf(a.text()))
                                && !isFact(a.text()))
                            c.error(
                                    s.getLine(),
                                    s instanceof RunStmt ? "run" : "use",
                                    "the handle " + baseOf(a.text())
                                            + " cannot be given to a task or tool: a handle is held by the script, never handed to anything a model could drive. Pass a fact, such as "
                                            + baseOf(a.text()) + ".status.");
                    if (s instanceof ReturnStmt r && r.getValue() != null) {
                        r.getValue()
                                .visitTexts(
                                        t -> {
                                            if (r.getValue() instanceof ValueExpr.Ref
                                                    && handles.contains(baseOf(t)))
                                                c.error(
                                                        s.getLine(),
                                                        "return",
                                                        "a handle cannot be returned: a child never outlives the run that started it. Return what it produced instead: wait "
                                                                + baseOf(t) + " timeout 5m -> result, then return result.");
                                        });
                    }
                });
    }

    private static void unboundedStarts(
            ScriptValidator.Checker c, List<Statement> statements, boolean inUnbounded) {
        for (Statement s : statements) {
            if (s instanceof StartStmt st && inUnbounded)
                c.error(
                        st.getLine(),
                        "start " + st.getWorkflow(),
                        "a start inside a loop needs the loop's max, so the number of children is bounded: write max 3 on the for each or loop.");
            boolean bounded = true;
            if (s instanceof ForEachStmt f) bounded = f.getMax() > 0;
            else if (s instanceof LoopStmt l) bounded = l.getMaxIterations() > 0;
            boolean inLoop = s instanceof ForEachStmt || s instanceof LoopStmt;
            for (List<Statement> inner : StatementWalker.nested(s))
                unboundedStarts(c, inner, inUnbounded || (inLoop && !bounded));
        }
    }

    // ---- handles must be started ----

    private static void isHandle(
            ScriptValidator.Checker c, Set<String> known, HandleRef ref, int line, String verb) {
        if (!known.contains(ref.name()))
            c.error(
                    line,
                    verb + " " + ref.describe(),
                    ref.name() + " is not a handle: nothing in this workflow binds it with start. Write start Child(...) budget { tokens: 1000 } -> "
                            + ref.name() + " first.");
    }

    private static void unwaited(
            ScriptValidator.Checker c,
            Set<String> handles,
            List<WaitStmt> waits,
            List<CancelStmt> cancels,
            List<SuperviseStmt> supervises,
            List<StartStmt> starts) {
        Set<String> used = new HashSet<>();
        waits.forEach(w -> used.add(w.getTarget().name()));
        cancels.forEach(w -> used.add(w.getTarget().name()));
        supervises.forEach(w -> used.add(w.getTarget().name()));
        for (StartStmt s : starts)
            if (!used.contains(s.getTarget().name()))
                c.warn(
                        s.getLine(),
                        "start " + s.getWorkflow(),
                        "the handle " + s.getTarget().name()
                                + " is never waited on, cancelled or supervised. The run cancels the child when the workflow ends; write wait "
                                + s.getTarget().name() + " timeout 5m -> result if you want its answer.");
    }

    private static void resultReads(ScriptValidator.Checker c, WorkflowDef w, Set<String> handles) {
        Set<String> guarded = new HashSet<>();
        Map<String, String> outcomes = new LinkedHashMap<>(); // the variable a single wait fills -> its handle
        StatementWalker.walk(
                w.getStatements(),
                s -> {
                    if (s instanceof WaitStmt x && !x.isAll() && x.getResultVariable() != null)
                        outcomes.put(x.getResultVariable(), x.getTarget().name());
                    String condition =
                            s instanceof AltStmt a ? a.getCondition() : s instanceof LoopStmt l ? l.getCondition() : null;
                    if (condition == null) return;
                    Matcher m = IS_OK.matcher(condition.strip());
                    if (m.matches()) guarded.add(m.group(1));
                    for (String h : handles) if (condition.contains(h + ".status")) guarded.add(h);
                });
        StatementWalker.walk(
                w.getStatements(),
                s -> {
                    for (SubstitutedText.Entry e : SubstitutedText.entries(s)) {
                        Matcher m = PLACEHOLDER.matcher(e.text());
                        while (m.find()) {
                            if (outcomes.containsKey(m.group(1)) && !guarded.contains(outcomes.get(m.group(1))))
                                c.warn(
                                        s.getLine(),
                                        e.position(),
                                        m.group(1) + " is read without checking the child finished: it holds no result unless the child completed, and reading it then is an error. Guard it: alt ("
                                                + outcomes.get(m.group(1)) + " is ok) { ... }.");
                            if (handles.contains(m.group(1))
                                    && m.group(3) != null
                                    && (m.group(3).equals(".result") || m.group(3).equals(".partial"))
                                    && !guarded.contains(m.group(1)))
                                c.warn(
                                        s.getLine(),
                                        e.position(),
                                        m.group(1) + m.group(3)
                                                + " is read without checking the child finished: it is an error unless the child completed. Guard it: alt ("
                                                + m.group(1) + " is ok) { ... }.");
                        }
                    }
                });
    }

    // ---- supervise ----

    private static void supervise(
            ScriptValidator.Checker c, LoomScript script, List<StartStmt> starts, SuperviseStmt s) {
        String construct = "supervise " + s.getTarget().describe();
        WorkflowDef child = childOf(script, starts, s.getTarget());
        for (SuperviseStmt.Counted rule : s.getCounted()) {
            if (rule.counter() == SuperviseStmt.Counter.COST)
                c.warn(
                        rule.line(),
                        construct,
                        "a cost rule needs a price table: without one (weave run --prices FILE) the rule has no prices to count with and never fires.");
        }
        for (SuperviseStmt.CodeAssertion a : s.getCode()) {
            switch (a.kind()) {
                case REACHES_WITHIN -> checkpointExists(c, script, child, a.name(), a.line(), construct);
                case NEVER_CALLS, CALLS_AT_MOST -> callable(c, script, child, a.name(), a.line(), construct);
                case CALLS_BEFORE -> {
                    callable(c, script, child, a.name(), a.line(), construct);
                    callable(c, script, child, a.other(), a.line(), construct);
                }
            }
        }
        for (SuperviseStmt.Judged j : s.getJudged()) {
            if (script.findEvaluator(j.evaluator()) == null)
                c.error(
                        j.line(),
                        construct,
                        "the evaluator " + j.evaluator()
                                + " is not declared. Declare it: evaluator " + j.evaluator() + " { model: \"gemini-2.5-flash\" }");
            if (j.everyMillis() != null && j.everyMillis() < JUDGE_FLOOR_MILLIS)
                c.error(
                        j.line(),
                        construct,
                        "a judge may be asked at most every 30 seconds: every " + j.everyMillis() / 1000
                                + "s is too often. A check is a model call; about 5 to 10 over the child's life is enough.");
            if (s.getBudget() == null)
                c.error(
                        j.line(),
                        construct,
                        "a judged assertion needs the supervise statement's own budget, which pays for the judge: supervise "
                                + s.getTarget().describe() + " budget { tokens: 20000 } { ... }");
        }
    }

    private static void steerable(
            ScriptValidator.Checker c, LoomScript script, String agent, int line, String construct) {
        AgentDef def = null;
        for (AgentDef a : script.getAgents()) if (a.getName().equals(agent)) def = a;
        if (def == null)
            c.error(line, construct, "there is no agent " + agent + " to steer. Name an agent of the child that is steerable.");
        else if (!def.isSteerable())
            c.error(
                    line,
                    construct,
                    "the agent " + agent
                            + " is not steerable: a parent can steer only the agents the child's author marked steerable: true. Add steerable: true to the agent, or steer another.");
    }

    // ---- what the child's script can do ----

    private static void checkpointExists(
            ScriptValidator.Checker c,
            LoomScript script,
            WorkflowDef child,
            String checkpoint,
            int line,
            String construct) {
        if (child == null) return;
        Set<String> found = new HashSet<>();
        reachable(script, child, new HashSet<>())
                .forEach(w -> StatementWalker.walk(w.getStatements(), s -> {
                    if (s instanceof CheckpointStmt cp) found.add(cp.getName());
                }));
        if (!found.contains(checkpoint))
            c.error(
                    line,
                    construct,
                    "the child declares no checkpoint \"" + checkpoint
                            + "\", so this can never happen. Its checkpoints: "
                            + (found.isEmpty() ? "none" : String.join(", ", found)) + ".");
    }

    private static void callable(
            ScriptValidator.Checker c,
            LoomScript script,
            WorkflowDef child,
            String name,
            int line,
            String construct) {
        Set<String> declared = new HashSet<>();
        script.getTools().forEach(t -> declared.add(t.getName()));
        script.getAgents().forEach(a -> declared.add(a.getName()));
        for (WorkflowDef w : script.getWorkflows())
            StatementWalker.walk(w.getStatements(), s -> {
                if (s instanceof RunStmt r) declared.add(r.getTaskName());
                if (s instanceof UseStmt u) declared.add(u.getTool());
            });
        if (!declared.contains(name)) {
            c.error(
                    line,
                    construct,
                    "\"" + name + "\" is not a tool, agent or task of this script, so the assertion cannot be checked. Names it knows: "
                            + String.join(", ", new java.util.TreeSet<>(declared)) + ".");
            return;
        }
        if (child == null) return;
        Set<String> used = new HashSet<>();
        for (WorkflowDef w : reachable(script, child, new HashSet<>())) {
            StatementWalker.walk(w.getStatements(), s -> {
                if (s instanceof DelegateStmt d) used.add(d.getTargetAgent());
                if (s instanceof HandoffStmt h) used.add(h.getTargetAgent());
                if (s instanceof BroadcastStmt b) used.addAll(b.getTargetAgents());
                if (s instanceof RunStmt r) used.add(r.getTaskName());
                if (s instanceof UseStmt u) used.add(u.getTool());
            });
        }
        for (String agent : new ArrayList<>(used))
            for (AgentDef a : script.getAgents()) if (a.getName().equals(agent)) used.addAll(a.getTools());
        if (!used.contains(name))
            c.warn(
                    line,
                    construct,
                    "the child's script never uses \"" + name + "\", so this assertion can never be violated.");
    }

    private static List<WorkflowDef> reachable(LoomScript script, WorkflowDef from, Set<String> seen) {
        List<WorkflowDef> out = new ArrayList<>();
        if (!seen.add(from.getName())) return out;
        out.add(from);
        StatementWalker.walk(
                from.getStatements(),
                s -> {
                    String next = s instanceof CallStmt cs ? cs.getWorkflowName() : s instanceof StartStmt st ? st.getWorkflow() : null;
                    WorkflowDef w = next == null ? null : find(script, next);
                    if (w != null) out.addAll(reachable(script, w, seen));
                });
        return out;
    }

    private static long longestSleepMillis(LoomScript script, WorkflowDef child, Set<String> seen) {
        long[] longest = {0};
        for (WorkflowDef w : reachable(script, child, seen))
            StatementWalker.walk(w.getStatements(), s -> {
                if (s instanceof SleepStmt z) longest[0] = Math.max(longest[0], z.getDuration().toMillis());
            });
        return longest[0];
    }

    /** The workflow a handle was started from, or null when the handle came in as a parameter. */
    private static WorkflowDef childOf(LoomScript script, List<StartStmt> starts, HandleRef ref) {
        for (StartStmt s : starts)
            if (s.getTarget().name().equals(ref.name())) return find(script, s.getWorkflow());
        return null;
    }

    private static WorkflowDef find(LoomScript script, String name) {
        for (WorkflowDef w : script.getWorkflows()) if (w.getName().equals(name)) return w;
        return null;
    }

    /** {@code handle.status} and the other facts may be passed on; the handle itself may not. */
    private static boolean isFact(String reference) {
        int dot = reference.indexOf('.');
        return dot > 0 && !reference.substring(0, dot).contains("[") && FACTS.contains(head(reference.substring(dot + 1)));
    }

    private static String head(String path) {
        int dot = path.indexOf('.');
        return dot < 0 ? path : path.substring(0, dot);
    }

    private static String baseOf(String reference) {
        int cut = reference.length();
        for (char ch : new char[] {'.', '['}) {
            int i = reference.indexOf(ch);
            if (i >= 0) cut = Math.min(cut, i);
        }
        return reference.substring(0, cut);
    }
}
