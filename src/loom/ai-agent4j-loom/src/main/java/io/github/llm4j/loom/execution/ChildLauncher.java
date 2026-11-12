package io.github.llm4j.loom.execution;

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
import java.util.IdentityHashMap;
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
            ChildScheduler.Task task, HarnessExecutor run, Long capTokens, Long capCalls, BigDecimal capCost) {}

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

        HarnessExecutor child = executor.deriveChild(childRun, runs.openJournal(childRun));
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
            tasks.put(handle, new Live(task, child, capTokens, capCalls, capCost));
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
            child.initialize();
            child.executeWorkflow(workflow, null, invoker);
            handle.complete(child.getContext().getVariable("result"));
        } catch (RunSuspended paused) {
            handle.pause(
                    paused.resumeAt() != null ? ChildStatus.SLEEPING : ChildStatus.WAITING,
                    paused.resumeAt());
        } catch (RunStopped stopped) {
            handle.end(ChildStatus.STOPPED, stopped.getMessage());
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
            if (task == null || h.status().isSettled()) continue;
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
        if (person)
            throw new RunSuspended(stepId, "waiting for a child that is waiting for a person");
        throw new RunSuspended(stepId, earliest, "waiting for a child that sleeps");
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
