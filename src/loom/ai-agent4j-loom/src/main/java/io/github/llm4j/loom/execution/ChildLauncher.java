package io.github.llm4j.loom.execution;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.ast.BudgetDef;
import io.github.llm4j.loom.ast.HandleRef;
import io.github.llm4j.loom.ast.RunStmt;
import io.github.llm4j.loom.ast.StartStmt;
import io.github.llm4j.loom.ast.WaitStmt;
import io.github.llm4j.loom.runtime.ChildHandle;
import io.github.llm4j.loom.runtime.ChildStatus;
import io.github.llm4j.loom.runtime.ConditionEvaluator;
import io.github.llm4j.loom.runtime.RunHalted;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.runtime.RunStopped;
import io.github.llm4j.loom.runtime.RunSuspended;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Starts child runs and waits for them (phase 33b). One belongs to each executor.
 *
 * <p>{@code start} writes its journal entry first and then launches, and the child's id is worked
 * out from the step (see {@link ChildIds}), so a parent that is killed anywhere between the two and
 * resumed attaches to the one child. {@code wait} journals what it saw, so a replay returns the
 * same outcome without waiting again.
 */
final class ChildLauncher {

    private static final Logger log = Logger.getLogger(ChildLauncher.class.getName());

    /** Journal kind of a {@code start}: the child's run id and workflow. */
    static final String START = "child";

    /** Journal kind of a {@code wait}: the outcome of each child it waited for. */
    static final String WAIT = "child-wait";

    private final HarnessExecutor executor;
    /** A child this launcher started: its task, its run and the most it may still spend. */
    private record Live(
            ChildScheduler.Task task, HarnessExecutor run, RunJournal journal, Long capTokens, Long capCalls, BigDecimal capCost) {}

    private final Map<ChildHandle, Live> tasks = new IdentityHashMap<>();

    ChildLauncher(HarnessExecutor executor) {
        this.executor = executor;
    }

    // ---- start ----

    void start(StartStmt stmt) {
        // One start at a time: the limits and the reservation are read and the child registered
        // together, so two branches cannot both take the last of the parent's budget.
        synchronized (tasks) {
            startLocked(stmt);
        }
    }

    private void startLocked(StartStmt stmt) {
        ChildRuns runs = executor.childRuns();
        if (runs == null)
            throw new IllegalStateException(
                    "start needs somewhere to keep the child's run, and this executor was given only"
                            + " a journal. Run it through LoomHost with persistence, or give the executor"
                            + " ChildRuns (HarnessExecutor.setChildRuns). Use call to run a workflow to"
                            + " completion without that.");
        checkLimits(stmt);
        reserve(stmt);
        String parentRun = executor.getRunId();
        if (parentRun == null)
            throw new IllegalStateException(
                    "start needs the run's id to work out the child's: call setRunId first");
        String stepId = executor.currentStepId();
        String childRun = ChildIds.derive(parentRun, stepId);

        RunJournal journal = executor.journal();
        Optional<RunJournal.Entry> recorded = journal.get(stepId);
        if (recorded.isPresent()
                && !(recorded.get().value() instanceof Map<?, ?> m && childRun.equals(m.get("run"))))
            throw new IllegalStateException(
                    "the journal entry of " + stepId + " is not a start of child " + childRun
                            + ": the script changed since the run began, or the journal is not this run's");

        if (stmt.getBudget() == null)
            throw new IllegalStateException(
                    "start " + stmt.getWorkflow() + " has no budget: a child's spending must be capped. Write budget { tokens: 100000 } on the start.");
        HarnessExecutor.Tracked<Map<String, Object>> args =
                executor.tracked(() -> argumentValues(stmt));
        if (recorded.isEmpty())
            journal.put(
                    stepId,
                    new RunJournal.Entry(
                            START, Map.of("run", childRun, "workflow", stmt.getWorkflow())));
        runs.record(childRun, parentRun, stmt.getWorkflow());

        RunJournal childJournal = runs.openJournal(childRun);
        HarnessExecutor child = executor.deriveChild(childRun, childJournal);
        args.value().forEach((name, value) -> child.bind(name, value, args.tag()));
        BudgetDef scripted = executor.loomScript().getBudget();
        Long capTokens = smaller(stmt.getBudget().getTokens(), scripted == null ? null : scripted.getTokens());
        Long capCalls = smaller(stmt.getBudget().getCalls(), scripted == null ? null : scripted.getCalls());
        BigDecimal capCost = smaller(stmt.getBudget().getCost(), scripted == null ? null : scripted.getCost());
        child.setBudgetOverrides(capTokens, capCalls, capCost);
        var enclosing = executor.enclosingBudgets();

        ChildHandle handle = new ChildHandle(childRun, stmt.getWorkflow());
        var invoker = executor.currentInvoker();
        String workflow = stmt.getWorkflow();
        ChildScheduler.Task task =
                executor.childScheduler()
                        .submit(
                                childRun,
                                () -> {
                                    try {
                                        child.installOuterBudgets(enclosing);
                                        runChild(child, handle, workflow, invoker);
                                    } finally {
                                        runs.settled(childRun, handle.status());
                                    }
                                });
        synchronized (tasks) {
            tasks.put(handle, new Live(task, child, childJournal, capTokens, capCalls, capCost));
        }
        executor.bind(boundName(stmt.getTarget()), handle, Provenance.NOT_MODEL);
        log.info("Started child " + childRun + " (" + workflow + ")");
    }

    /** Refuses a start past a limit, naming it; nothing is queued and nothing is journaled (D14). */
    private void checkLimits(StartStmt stmt) {
        ChildLimits ceiling = executor.childLimits();
        Integer scriptChildren = executor.loomScript().getMaxChildren();
        Integer scriptDepth = executor.loomScript().getMaxDepth();
        if (scriptChildren != null && scriptChildren > ceiling.ceilingChildren())
            throw new IllegalStateException(
                    "max_children: " + scriptChildren + " is above this host's ceiling of "
                            + ceiling.ceilingChildren() + ". Lower it, or ask the host to raise its ceiling.");
        if (scriptDepth != null && scriptDepth > ceiling.ceilingDepth())
            throw new IllegalStateException(
                    "max_depth: " + scriptDepth + " is above this host's ceiling of "
                            + ceiling.ceilingDepth() + ". Lower it, or ask the host to raise its ceiling.");
        int maxChildren = scriptChildren != null ? scriptChildren : Math.min(ChildLimits.DEFAULT_MAX_CHILDREN, ceiling.ceilingChildren());
        int maxDepth = scriptDepth != null ? scriptDepth : Math.min(ChildLimits.DEFAULT_MAX_DEPTH, ceiling.ceilingDepth());
        if (executor.childDepth() + 1 > maxDepth)
            throw new IllegalStateException(
                    "start " + stmt.getWorkflow() + " would nest children " + (executor.childDepth() + 1)
                            + " levels deep, past max_depth: " + maxDepth
                            + ". A started workflow that starts workflows is one level deeper; raise max_depth or start fewer levels.");
        long live;
        synchronized (tasks) {
            live = tasks.keySet().stream().filter(h -> !h.status().isSettled()).count();
        }
        if (live >= maxChildren)
            throw new IllegalStateException(
                    "start " + stmt.getWorkflow() + " would be child number " + (live + 1)
                            + ", past max_children: " + maxChildren
                            + ". A start does not queue: wait for or cancel a child first, or raise max_children.");
    }

    /**
     * Refuses a start the parent could not pay for (D11): the child's cap, with what the other live
     * children could still spend, must fit in what the parent has left. A child's spending is
     * already in the parent's budget, so a live child holds back only what it has not yet spent.
     * Nothing is journaled for a refused start.
     */
    private void reserve(StartStmt stmt) {
        BudgetDef cap = stmt.getBudget();
        long heldTokens = 0;
        long heldCalls = 0;
        BigDecimal heldCost = BigDecimal.ZERO;
        for (Map.Entry<ChildHandle, Live> e : tasks.entrySet()) {
            if (e.getKey().status().isSettled()) continue;
            Live live = e.getValue();
            var own = live.run().getRunBudget();
            var spent = own == null ? null : own.spent();
            if (live.capTokens() != null)
                heldTokens += Math.max(0, live.capTokens() - (spent == null ? 0 : spent.tokens()));
            if (live.capCalls() != null)
                heldCalls += Math.max(0, live.capCalls() - (spent == null ? 0 : spent.calls()));
            if (live.capCost() != null)
                heldCost = heldCost.add(live.capCost().subtract(spent == null ? BigDecimal.ZERO : spent.cost()).max(BigDecimal.ZERO));
        }
        for (var budget : executor.enclosingBudgets()) {
            var left = budget.remaining();
            String who = "the budget " + budget.name();
            if (cap.getTokens() != null && left.tokens().isPresent() && cap.getTokens() > left.tokens().getAsLong() - heldTokens)
                refuseReservation(stmt, who, cap.getTokens() + " tokens", left.tokens().getAsLong(), heldTokens);
            if (cap.getCalls() != null && left.calls().isPresent() && cap.getCalls() > left.calls().getAsLong() - heldCalls)
                refuseReservation(stmt, who, cap.getCalls() + " calls", left.calls().getAsLong(), heldCalls);
            if (cap.getCost() != null && left.cost().isPresent() && cap.getCost().compareTo(left.cost().get().subtract(heldCost)) > 0)
                refuseReservation(stmt, who, "$" + cap.getCost().toPlainString(), left.cost().get().longValue(), heldCost.longValue());
        }
    }

    private static void refuseReservation(StartStmt stmt, String who, String wanted, long left, long held) {
        throw new IllegalStateException(
                "start " + stmt.getWorkflow() + " cannot reserve " + wanted + ": " + who + " has " + left
                        + " left and the other live children could still spend " + held
                        + ". Start fewer children, give each a smaller budget, or wait for one to finish first.");
    }

    private static <T extends Comparable<T>> T smaller(T a, T b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.compareTo(b) <= 0 ? a : b;
    }

    /** The body of a child: its whole run, ending in a status on the handle. */
    private void runChild(
            HarnessExecutor child,
            ChildHandle handle,
            String workflow,
            io.github.llm4j.secret.InvokerCredentials invoker) {
        try {
            Optional<RunJournal.Entry> marker = child.journal().get(CANCEL_MARKER);
            if (marker.isPresent()) { // cancelled before it ran, or before a crash: it stays so
                settleCancelled(child, handle, marker.get());
                return;
            }
            child.initialize();
            child.executeWorkflow(workflow, null, invoker);
            handle.complete(child.getContext().getVariable("result"));
        } catch (RunSuspended paused) {
            handle.pause(
                    paused.resumeAt() != null ? ChildStatus.SLEEPING : ChildStatus.WAITING,
                    paused.resumeAt());
        } catch (RunStopped stopped) {
            Optional<RunJournal.Entry> marker = child.journal().get(CANCEL_MARKER);
            if (marker.isPresent()) settleCancelled(child, handle, marker.get());
            else handle.end(ChildStatus.STOPPED, stopped.getMessage());
        } catch (RunHalted halted) {
            handle.end(ChildStatus.FAILED, "halted: " + halted.reason());
        } catch (io.github.llm4j.budget.BudgetExceeded exhausted) {
            handle.end(ChildStatus.FAILED, "budget: " + exhausted.getMessage());
        } catch (RuntimeException failed) {
            handle.end(ChildStatus.FAILED, String.valueOf(failed.getMessage()));
        } catch (Error died) {
            handle.end(ChildStatus.FAILED, String.valueOf(died.getMessage()));
            throw died;
        }
    }

    private Map<String, Object> argumentValues(StartStmt stmt) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (RunStmt.Arg a : stmt.getArgs()) {
            switch (a.kind()) {
                case STRING -> out.put(a.name(), executor.resolve(a.text()));
                case BOOLEAN -> out.put(a.name(), String.valueOf(Boolean.valueOf(a.text())));
                case NUMBER -> out.put(a.name(), a.text());
                case REFERENCE -> {
                    String head = a.text().contains(".") ? a.text().substring(0, a.text().indexOf('.')) : a.text();
                    Object value =
                            executor.view().getAll().containsKey(head)
                                    ? ConditionEvaluator.resolvePath(a.text(), executor.view())
                                    : null;
                    if (value == null)
                        throw new IllegalStateException(
                                "argument " + a.name() + " of start " + stmt.getWorkflow()
                                        + " refers to " + a.text() + ", which has no value, so no child was started");
                    executor.touch(a.text());
                    out.put(a.name(), value instanceof String ? value : String.valueOf(value));
                }
            }
        }
        return out;
    }

    // ---- wait ----

    void await(WaitStmt stmt) {
        if (stmt.getTimeoutMillis() == null)
            throw new IllegalStateException("wait needs a timeout: " + stmt.getTarget().describe());
        String stepId = executor.currentStepId();
        Map<String, ChildHandle> handles = handlesOf(stmt);

        Optional<RunJournal.Entry> recorded = executor.journal().get(stepId);
        if (recorded.isPresent() && WAIT.equals(recorded.get().kind())) {
            applyRecorded(handles, recorded.get().value());
        } else {
            settle(handles, stmt.getTimeoutMillis());
            suspendIfNothingCanRun(stepId, handles);
            executor.journal().put(stepId, new RunJournal.Entry(WAIT, outcomesOf(handles)));
        }
        bindOutcome(stmt, handles);
        runHandlers(stmt, handles);
    }

    private void settle(Map<String, ChildHandle> handles, long timeoutMillis) {
        long deadline = executor.nowMillis() + timeoutMillis;
        for (ChildHandle h : handles.values()) {
            ChildScheduler.Task task;
            synchronized (tasks) {
                Live live = tasks.get(h);
                task = live == null ? null : live.task();
            }
            if (task == null) continue; // a settled child's task may still be recording its end
            try {
                task.await(Math.max(0, deadline - executor.nowMillis()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for " + h, e);
            }
        }
    }

    /**
     * The tree suspends, and the process may go, only when nothing can run (D8): every child that is
     * not settled is asleep or waiting for a person. The parent then holds no thread; a resume
     * replays the start, which re-attaches to each child by its id, and waits again. Nothing is
     * journaled for the wait, so the replay waits afresh.
     */
    private volatile RunSuspended suspendedByWait;

    private void suspendIfNothingCanRun(String stepId, Map<String, ChildHandle> handles) {
        java.time.Instant earliest = null;
        boolean person = false;
        boolean paused = false;
        for (ChildHandle h : handles.values()) {
            if (h.status().isSettled()) continue;
            if (h.status() == ChildStatus.RUNNING) return; // it may still finish: the wait timed out
            paused = true;
            if (h.wakes() == null) person = true;
            else if (earliest == null || h.wakes().isBefore(earliest)) earliest = h.wakes();
        }
        if (!paused) return;
        RunSuspended suspension =
                person
                        ? new RunSuspended(stepId, "waiting for a child that is waiting for a person")
                        : new RunSuspended(stepId, earliest, "waiting for a child that sleeps");
        suspendedByWait = suspension;
        throw suspension;
    }

    private Map<String, Object> outcomesOf(Map<String, ChildHandle> handles) {
        Map<String, Object> out = new LinkedHashMap<>();
        handles.forEach((key, h) -> out.put(key, outcome(h)));
        return out;
    }

    private static Map<String, Object> outcome(ChildHandle h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", h.status().word());
        if (h.status() == ChildStatus.COMPLETED) m.put("result", h.fact("result"));
        if (h.fact("reason") != null) m.put("reason", h.fact("reason"));
        if (h.fact("failure") != null) m.put("failure", h.fact("failure"));
        return m;
    }

    /** A replayed wait: the handles take what the first run saw, and no one is waited for again. */
    private void applyRecorded(Map<String, ChildHandle> handles, Object value) {
        if (!(value instanceof Map<?, ?> all)) return;
        handles.forEach(
                (key, h) -> {
                    if (!(all.get(key) instanceof Map<?, ?> m)) return;
                    ChildStatus status = statusOf(String.valueOf(m.get("status")));
                    if (status == ChildStatus.COMPLETED) h.complete(m.get("result"));
                    else if (status.isSettled())
                        h.end(
                                status,
                                String.valueOf(m.get(status == ChildStatus.FAILED ? "failure" : "reason")));
                });
    }

    private static ChildStatus statusOf(String word) {
        for (ChildStatus s : ChildStatus.values()) if (s.word().equals(word)) return s;
        throw new IllegalStateException("the journal records an unknown child status: " + word);
    }

    private void bindOutcome(WaitStmt stmt, Map<String, ChildHandle> handles) {
        if (stmt.getResultVariable() == null) return;
        var unchecked = Provenance.UNCHECKED; // a child's answer is model text
        if (stmt.isAll()) {
            executor.bind(stmt.getResultVariable(), outcomesOf(handles), unchecked);
        } else {
            ChildHandle only = handles.values().iterator().next();
            if (only.status() == ChildStatus.COMPLETED)
                executor.bind(stmt.getResultVariable(), only.fact("result"), unchecked);
        }
    }

    private void runHandlers(WaitStmt stmt, Map<String, ChildHandle> handles) {
        boolean failed = handles.values().stream().anyMatch(h -> h.status() == ChildStatus.FAILED);
        boolean pending = handles.values().stream().anyMatch(h -> !h.status().isSettled());
        if (pending && !stmt.getOnTimeout().isEmpty()) executor.runBlockAt(stmt.getOnTimeout(), "wt");
        if (failed && !stmt.getOnFailure().isEmpty()) executor.runBlockAt(stmt.getOnFailure(), "wf");
    }

    // ---- supervise ----

    /** One {@code supervise} block watching one child. */
    private static final class Watch {
        final ChildHandle handle;
        final Live live;
        final SuperviseEvaluator evaluator;
        final String stepId;
        final int firstSequence;
        int sequence;
        long lastTick = -1;
        long runningMillis;
        long lastProgress = -1;
        long signature = -1;
        List<JudgeSchedule> schedules = List.of();
        boolean[] judgedFiredOnce = new boolean[0];
        io.github.llm4j.budget.Budget judgeBudget;
        io.github.llm4j.loom.ast.SuperviseStmt stmt;
        List<io.github.llm4j.budget.Budget> enclosing = List.of();

        Watch(ChildHandle handle, Live live, SuperviseEvaluator evaluator, String stepId, int sequence) {
            this.handle = handle;
            this.live = live;
            this.evaluator = evaluator;
            this.stepId = stepId;
            this.firstSequence = sequence;
            this.sequence = sequence;
        }
    }

    private final List<Watch> watches = new java.util.concurrent.CopyOnWriteArrayList<>();
    private java.util.concurrent.ScheduledExecutorService ticker;

    /**
     * Attaches the rules of a {@code supervise} block to a child for as long as it runs (D21). The
     * rules that fired before a restart are read back from the journal, so a resume does not steer
     * or cancel twice. Only counted rules run in this build; the other kinds fail closed.
     */
    void supervise(io.github.llm4j.loom.ast.SuperviseStmt stmt) {
        ChildHandle handle = singleHandle(stmt.getTarget());
        Live live;
        synchronized (tasks) {
            live = tasks.get(handle);
        }
        if (live == null)
            throw new IllegalStateException(
                    stmt.getTarget().describe() + " is a handle this run did not start, so it cannot supervise it");
        String stepId = executor.currentStepId();
        String prefix = stepId + FIRE;
        java.util.Set<Integer> before = new java.util.HashSet<>();
        int count = 0;
        for (Map.Entry<String, RunJournal.Entry> e : executor.journal().all().entrySet()) {
            if (!e.getKey().startsWith(prefix)) continue;
            count++;
            if (e.getValue().value() instanceof Map<?, ?> m && m.get("rule") instanceof Number n && "fired".equals(m.get("outcome")))
                before.add(n.intValue());
        }
        for (io.github.llm4j.loom.ast.SuperviseStmt.CodeAssertion a : stmt.getCode())
            if (a.kind() == io.github.llm4j.loom.ast.SuperviseStmt.AssertionKind.NEVER_CALLS)
                live.run().forbid(a.name()); // preventive: refused before it runs, narrowing the child
        Watch watch = new Watch(handle, live, new SuperviseEvaluator(stmt, before), stepId, count);
        watch.lastTick = executor.nowMillis(); // the child's time with this block begins now
        watch.lastProgress = watch.lastTick;
        watch.signature = signatureOf(live);
        if (!stmt.getJudged().isEmpty()) {
            if (stmt.getBudget() == null)
                throw new IllegalStateException(
                        "supervise " + stmt.getTarget().describe() + " has a judged assertion and no budget to pay for the judge: write supervise "
                                + stmt.getTarget().describe() + " budget { tokens: 20000 } { ... }");
            watch.judgeBudget = executor.newJudgeBudget("judge of " + stmt.getTarget().describe(), stmt.getBudget());
            watch.enclosing = executor.enclosingBudgets();
            List<JudgeSchedule> schedules = new ArrayList<>();
            for (int j = 0; j < stmt.getJudged().size(); j++) {
                String judgePrefix = stepId + JUDGE + j + ".";
                int asked = (int) executor.journal().all().keySet().stream().filter(k -> k.startsWith(judgePrefix)).count();
                schedules.add(new JudgeSchedule(stmt.getJudged().get(j), watch.signature, asked));
            }
            watch.schedules = schedules;
            watch.judgedFiredOnce = new boolean[stmt.getJudged().size()];
            watch.stmt = stmt;
        }
        watches.add(watch);
        startTicking();
    }

    /** Journal key infix of a judgement, in the parent's journal: {@code <step>#judge.<rule>.<n>}. */
    static final String JUDGE = "#judge.";

    /**
     * The judged assertions, last in the order (D30) and skipped when a rule already ended the
     * tick. A judgement is journaled with the child's position when it is made, so a resume counts
     * it and never asks again (D25); a judge that cannot answer still counts, so a failing judge is
     * bounded too.
     */
    private void runJudges(Watch w, SuperviseEvaluator.Facts facts) {
        if (w.stmt == null) return;
        int steps = w.live.journal().all().size();
        long signature = signatureOf(w.live);
        for (int j = 0; j < w.stmt.getJudged().size(); j++) {
            var rule = w.stmt.getJudged().get(j);
            JudgeSchedule schedule = w.schedules.get(j);
            JudgeSchedule.Decision decision = schedule.decide(facts.elapsedMillis(), steps, signature);
            int ruleIndex = w.stmt.getCounted().size() + w.stmt.getCode().size() + j;
            switch (decision) {
                case NOT_YET, SKIP_NO_PROGRESS, STOPPED -> {
                    continue;
                }
                case LIMIT_REACHED -> {
                    note(w, ruleIndex, "judge limit reached");
                    continue;
                }
                case BUDGET_USED_UP -> {
                    note(w, ruleIndex, "judge budget used up");
                    continue;
                }
                case ASK -> {}
            }
            Judge.Verdict verdict;
            String detail = "";
            try {
                var def = executor.loomScript().findEvaluator(rule.evaluator());
                LLMClient client = executor.judgeClient(def, w.judgeBudget, w.enclosing);
                String shown =
                        Judge.facts(facts, steps, facts.trajectory(), rule.seeingContent() ? partialOf(w.live.run()) : null);
                verdict = Judge.ask(client, rule.text(), shown);
            } catch (io.github.llm4j.budget.BudgetExceeded exhausted) {
                schedule.budgetUsedUp();
                note(w, ruleIndex, "judge budget used up");
                continue;
            } catch (RuntimeException failed) {
                verdict = Judge.Verdict.UNREADABLE;
                detail = "the judge could not answer: " + failed.getMessage();
            }
            schedule.asked(facts.elapsedMillis(), steps, signature);
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("rule", ruleIndex);
            record.put("verdict", verdict.name().toLowerCase());
            record.put("detail", detail);
            record.put("steps", steps);
            record.put("elapsedMillis", facts.elapsedMillis());
            record.put("content", rule.seeingContent());
            executor.journal().put(w.stepId + JUDGE + j + "." + (schedule.askedCount() - 1), new RunJournal.Entry("judge", record));
            executor.audit("child_judge", record);
            if (verdict != Judge.Verdict.FAIL || (rule.once() && w.judgedFiredOnce[j])) continue;
            w.judgedFiredOnce[j] = true;
            boolean ended = false;
            boolean steered = false;
            for (var action : rule.actions()) {
                if (action.kind() == io.github.llm4j.loom.ast.SuperviseDef.Do.STEER) {
                    if (steered) continue; // one steer a tick
                    steered = true;
                }
                apply(w, new SuperviseEvaluator.Firing(ruleIndex, action, "fired", action.kind() != io.github.llm4j.loom.ast.SuperviseDef.Do.STEER), facts);
                if (action.kind() != io.github.llm4j.loom.ast.SuperviseDef.Do.STEER) {
                    ended = true;
                    break;
                }
            }
            if (ended) return;
        }
    }

    private void note(Watch w, int ruleIndex, String text) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("rule", ruleIndex);
        record.put("outcome", "note");
        record.put("detail", text);
        executor.journal().put(w.stepId + FIRE + w.sequence++, new RunJournal.Entry("supervise", record));
        executor.audit("child_supervise", record);
    }

    /** Journal key infix of what a supervise rule did, in the parent's journal. */
    static final String FIRE = "#fire.";

    private synchronized void startTicking() {
        long every = executor.superviseTick().toMillis();
        if (every <= 0 || ticker != null) return;
        ticker =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "loom-supervise");
                            t.setDaemon(true);
                            return t;
                        });
        ticker.scheduleWithFixedDelay(this::tickSafely, every, every, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void tickSafely() {
        try {
            tick();
        } catch (RuntimeException e) {
            log.log(java.util.logging.Level.WARNING, "a supervise tick failed: " + e.getMessage(), e);
        }
    }

    private synchronized void stopTicking() {
        if (ticker != null) ticker.shutdownNow();
        ticker = null;
    }

    /**
     * One look at every watched child: reads its counters, lets each block decide, carries out what
     * it decided and journals every firing and every skip with the child's position (D30). Called
     * every tick by the supervisor's own thread, and by a test when it moves its clock.
     */
    void tick() {
        long now = executor.nowMillis();
        for (Watch w : watches) {
            boolean settled = w.handle.status().isSettled();
            synchronized (w) {
                // a last look when the child has just settled, so a violation that ended it is recorded
                SuperviseEvaluator.Facts facts = factsOf(w, now);
                boolean ended = false;
                for (SuperviseEvaluator.Firing firing : w.evaluator.evaluate(facts)) {
                    apply(w, firing, facts);
                    ended |= firing.endsEvaluation() && firing.delivered();
                }
                if (!ended && !settled) runJudges(w, facts);
            }
            if (settled) watches.remove(w);
        }
        if (watches.isEmpty()) stopTicking();
    }

    /** A number that changes whenever the child does something: spends, calls or records a step. */
    private static long signatureOf(Live live) {
        var budget = live.run().getRunBudget();
        var spent = budget == null ? null : budget.spent();
        return (spent == null ? 0 : spent.tokens() * 31 + spent.calls() * 17) + live.journal().all().size();
    }

    private SuperviseEvaluator.Facts factsOf(Watch w, long now) {
        var budget = w.live.run().getRunBudget();
        var spent = budget == null ? null : budget.spent();
        long tokens = spent == null ? 0 : spent.tokens();
        long calls = spent == null ? 0 : spent.calls();
        BigDecimal cost = spent == null ? BigDecimal.ZERO : spent.cost();
        boolean running = w.handle.status() == ChildStatus.RUNNING;
        long signature = signatureOf(w.live);
        if (running) w.runningMillis += now - w.lastTick;
        if (signature != w.signature || !running) w.lastProgress = now; // work done, or nothing to do
        w.signature = signature;
        w.lastTick = now;
        return new SuperviseEvaluator.Facts(
                tokens, calls, cost, w.runningMillis, running ? now - w.lastProgress : 0,
                w.live.run().trajectory());
    }

    private void apply(Watch w, SuperviseEvaluator.Firing firing, SuperviseEvaluator.Facts facts) {
        String origin = "supervise of " + executor.getRunId() + ", rule " + firing.rule();
        String outcome = firing.outcome();
        int delivered = 0;
        if (firing.delivered()) {
            var action = firing.action();
            switch (action.kind()) {
                case STEER -> {
                    delivered = w.live.run().steer("*", action.text(), origin);
                    outcome = delivered > 0 ? "fired" : "fired: not delivered, no steerable agent was running";
                }
                case WRAP_UP -> delivered = w.live.run().wrapUp("*", "supervise rule " + firing.rule(), origin);
                case CANCEL -> cancelChild(w.handle, w.live, action.outcome(), "supervise rule " + firing.rule() + " cancelled it");
            }
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("rule", firing.rule());
        record.put("action", firing.action().kind().name());
        record.put("outcome", outcome.startsWith("fired") ? "fired" : outcome);
        record.put("detail", outcome);
        record.put("delivered", delivered);
        record.put("tokens", facts.tokens());
        record.put("calls", facts.calls());
        record.put("elapsedMillis", facts.elapsedMillis());
        record.put("idleMillis", facts.idleMillis());
        record.put("at", w.live.journal().all().size()); // the child's position: how many steps it had recorded
        executor.journal().put(w.stepId + FIRE + w.sequence++, new RunJournal.Entry("supervise", record));
        executor.audit("child_supervise", record);
    }

    // ---- steer ----

    /** Journal kind of a {@code steer} statement: who was told, and how many agents took it. */
    static final String STEER = "child-steer";

    /**
     * Tells one {@code steerable} agent of a child what to do next (D12). The text is literal, from
     * the script. If no such agent is running nothing is delivered, which is recorded and is not an
     * error. Recorded in the parent's journal, so a replay never sends it again.
     */
    void steer(io.github.llm4j.loom.ast.SteerStmt stmt) {
        String stepId = executor.currentStepId();
        if (executor.journal().get(stepId).isPresent()) return; // sent before a restart: not sent again
        boolean steerable =
                executor.loomScript().getAgents().stream()
                        .anyMatch(a -> a.getName().equals(stmt.getAgent()) && a.isSteerable());
        if (!steerable)
            throw new IllegalStateException(
                    "steer " + stmt.getTarget().describe() + " " + stmt.getAgent() + ": the agent "
                            + stmt.getAgent() + " is not steerable, so a parent may not steer it. Mark it steerable: true in the child's script.");
        ChildHandle handle = singleHandle(stmt.getTarget());
        Live live;
        synchronized (tasks) {
            live = tasks.get(handle);
        }
        if (live == null)
            throw new IllegalStateException(
                    stmt.getTarget().describe() + " is a handle this run did not start, so it cannot steer it");
        int delivered =
                handle.status().isSettled()
                        ? 0
                        : live.run().steer(stmt.getAgent(), stmt.getText(), "parent " + executor.getRunId() + ", line " + stmt.getLine());
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("agent", stmt.getAgent());
        record.put("delivered", delivered);
        record.put("text", stmt.getText());
        executor.journal().put(stepId, new RunJournal.Entry(STEER, record));
        Map<String, Object> audit = new LinkedHashMap<>(record);
        audit.put("child", live.run().getRunId());
        audit.put("outcome", delivered > 0 ? "delivered" : "not delivered");
        executor.audit("child_steer", audit);
    }

    /**
     * The parent suspended. When that is not its {@code wait} finding nothing can run, nothing is
     * awake to supervise the children, so they are paused too (D29): each is asked to suspend at its
     * next step, which lets an effect under way finish first. A resume replays the start and the
     * child goes on from its journal.
     */
    void onParentSuspended(RunSuspended paused) {
        if (paused == suspendedByWait) return;
        List<Live> going = new ArrayList<>();
        synchronized (tasks) {
            for (Map.Entry<ChildHandle, Live> e : tasks.entrySet())
                if (!e.getKey().status().isSettled()) going.add(e.getValue());
        }
        for (Live live : going)
            live.run().control().request(RunControl.Action.SUSPEND, "parent suspended");
    }

    // ---- cancel ----

    /** Journal key, in the child's own journal, of a cancel: it survives a restart (D13). */
    static final String CANCEL_MARKER = "#cancel";

    /** Journal kind of a {@code cancel} statement, in the parent's journal. */
    static final String CANCEL = "child-cancel";

    void cancel(io.github.llm4j.loom.ast.CancelStmt stmt) {
        if (stmt.getRewindCheckpoint() != null)
            throw new IllegalStateException(
                    "cancel " + stmt.getTarget().describe() + " rewind to " + stmt.getRewindCheckpoint()
                            + " cannot run yet: sending a child back to a checkpoint is not available in this build.");
        String stepId = executor.currentStepId();
        ChildHandle handle = singleHandle(stmt.getTarget());
        Live live;
        synchronized (tasks) {
            live = tasks.get(handle);
        }
        if (live == null)
            throw new IllegalStateException(
                    stmt.getTarget().describe() + " is a handle this run did not start, so it cannot cancel it");
        String reason =
                stmt.getReason() != null
                        ? stmt.getReason()
                        : "cancelled by the parent script, line " + stmt.getLine();
        cancelChild(handle, live, stmt.getOutcome(), reason);
        if (executor.journal().get(stepId).isEmpty())
            executor.journal()
                    .put(
                            stepId,
                            new RunJournal.Entry(
                                    CANCEL, Map.of("status", handle.status().word(), "reason", reason)));
    }

    /**
     * Asks a child to stop. Idempotent, and a settled child is left alone (a recorded no-op). The
     * cancel is written to the child's journal first, so a restart cannot run it again. A sleeping
     * or waiting child settles at once; a running one stops at its next step, because an effect
     * under way always finishes and is journaled first (there is no forced kill).
     */
    private void cancelChild(
            ChildHandle handle, Live live, io.github.llm4j.loom.ast.OutcomeStmt.Kind outcome, String reason) {
        if (handle.status().isSettled()) return;
        live.journal()
                .put(
                        CANCEL_MARKER,
                        new RunJournal.Entry(
                                "cancel",
                                Map.of("reason", reason, "outcome", outcome == null ? "" : outcome.phrase())));
        if (handle.status() == ChildStatus.SLEEPING || handle.status() == ChildStatus.WAITING) {
            handle.cancelled(reason, outcome == io.github.llm4j.loom.ast.OutcomeStmt.Kind.USE_PARTIAL ? partialOf(live.run()) : null);
            executor.childRuns().settled(live.run().getRunId(), ChildStatus.CANCELLED);
            return;
        }
        live.run().control().request(RunControl.Action.STOP, "parent: " + reason);
    }

    /** What a child had produced: its variables, the runtime's own left out. */
    private static Map<String, Object> partialOf(HarnessExecutor child) {
        Map<String, Object> out = new LinkedHashMap<>();
        child.getContext().getAll().forEach((k, v) -> {
            if (!k.startsWith("_") && !k.startsWith("#")) out.put(k, v);
        });
        return out;
    }

    private void settleCancelled(HarnessExecutor child, ChildHandle handle, RunJournal.Entry marker) {
        String reason = "cancelled";
        boolean keep = false;
        if (marker.value() instanceof Map<?, ?> m) {
            reason = String.valueOf(m.get("reason"));
            keep = io.github.llm4j.loom.ast.OutcomeStmt.Kind.USE_PARTIAL.phrase().equals(m.get("outcome"));
        }
        handle.cancelled(reason, keep ? partialOf(child) : null);
    }

    /**
     * The parent has ended (it finished, halted, failed or was stopped): every child still going
     * is cancelled with {@code reason}, and the end waits for them to settle for at most the grace
     * period. One still unsettled after it is recorded as cancelled with a note that it did not
     * settle; its journal shows where it stopped (decision D10).
     */
    void cancelAll(String reason) {
        stopTicking();
        watches.clear();
        List<Map.Entry<ChildHandle, Live>> going = new ArrayList<>();
        synchronized (tasks) {
            for (Map.Entry<ChildHandle, Live> e : tasks.entrySet())
                if (!e.getKey().status().isSettled()) going.add(e);
        }
        if (going.isEmpty()) return;
        for (Map.Entry<ChildHandle, Live> e : going) cancelChild(e.getKey(), e.getValue(), null, reason);
        long deadline = executor.nowMillis() + executor.childLimits().settleGrace().toMillis();
        for (Map.Entry<ChildHandle, Live> e : going) {
            ChildHandle handle = e.getKey();
            if (handle.status().isSettled()) continue;
            try {
                e.getValue().task().await(Math.max(0, deadline - executor.nowMillis()));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            if (!handle.status().isSettled()) {
                handle.cancelled(
                        reason + "; it did not settle within the grace period of "
                                + executor.childLimits().settleGrace().toSeconds() + " s",
                        null);
                executor.childRuns().settled(e.getValue().run().getRunId(), ChildStatus.CANCELLED);
            }
        }
    }

    /** The one handle {@code ref} names, for a statement that acts on a single child. */
    private ChildHandle singleHandle(HandleRef ref) {
        Object base = executor.view().getVariable(ref.name());
        Object found = base;
        if (ref.isKeyed()) {
            String key = ref.keyIsLiteral() ? ref.key() : String.valueOf(ConditionEvaluator.resolvePath(ref.key(), executor.view()));
            found = base instanceof Map<?, ?> map ? map.get(key) : null;
        }
        if (found instanceof ChildHandle h) return h;
        throw new IllegalStateException(
                ref.describe() + " holds no handle at this point: start the child before using it");
    }

    // ---- handles in variables ----

    private static String boundName(HandleRef ref) {
        return ref.isKeyed() ? ref.name() + "[" + ref.key() + "]" : ref.name();
    }

    /** The handles a wait is about: the one it names, or every one in a keyed variable for {@code all}. */
    private Map<String, ChildHandle> handlesOf(WaitStmt stmt) {
        HandleRef ref = stmt.getTarget();
        Object base = executor.view().getVariable(ref.name());
        Map<String, ChildHandle> out = new LinkedHashMap<>();
        if (ref.isKeyed()) {
            String key = ref.keyIsLiteral() ? ref.key() : String.valueOf(ConditionEvaluator.resolvePath(ref.key(), executor.view()));
            if (base instanceof Map<?, ?> map && map.get(key) instanceof ChildHandle h) out.put(key, h);
        } else if (base instanceof ChildHandle h) {
            out.put(ref.name(), h);
        } else if (stmt.isAll() && base instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                if (v instanceof ChildHandle h) out.put(String.valueOf(k), h);
            });
        }
        if (out.isEmpty())
            throw new IllegalStateException(
                    ref.describe() + " holds no handle at this point: start the child before waiting for it");
        return out;
    }
}
