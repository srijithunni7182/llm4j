package io.github.llm4j.loom.execution;

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
    private final Map<ChildHandle, ChildScheduler.Task> tasks = new IdentityHashMap<>();

    ChildLauncher(HarnessExecutor executor) {
        this.executor = executor;
    }

    // ---- start ----

    void start(StartStmt stmt) {
        ChildRuns runs = executor.childRuns();
        if (runs == null)
            throw new IllegalStateException(
                    "start needs somewhere to keep the child's run, and this executor was given only"
                            + " a journal. Run it through LoomHost with persistence, or give the executor"
                            + " ChildRuns (HarnessExecutor.setChildRuns). Use call to run a workflow to"
                            + " completion without that.");
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
        if (stmt.getBudget() != null)
            child.setBudgetOverrides(
                    stmt.getBudget().getTokens(),
                    stmt.getBudget().getCalls(),
                    stmt.getBudget().getCost());

        ChildHandle handle = new ChildHandle(childRun, stmt.getWorkflow());
        var invoker = executor.currentInvoker();
        String workflow = stmt.getWorkflow();
        ChildScheduler.Task task =
                executor.childScheduler()
                        .submit(
                                childRun,
                                () -> {
                                    try {
                                        runChild(child, handle, workflow, invoker);
                                    } finally {
                                        runs.settled(childRun, handle.status());
                                    }
                                });
        synchronized (tasks) {
            tasks.put(handle, task);
        }
        executor.bind(boundName(stmt.getTarget()), handle, Provenance.NOT_MODEL);
        log.info("Started child " + childRun + " (" + workflow + ")");
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
                task = tasks.get(h);
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
