package io.github.llm4j.loom.execution;

import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskContext;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskNotPerformed;
import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.agent.tool.EffectPolicy;
import io.github.llm4j.loom.ast.RunStmt;
import io.github.llm4j.loom.runtime.ConditionEvaluator;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.tools.CanonicalArgs;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runs {@code run Task(...) -> result} steps: deterministic code, no model, no tokens. Journaled and replayed like a delegate.
 *
 * <p>A task that {@link TaskEffect#CHANGES changes things} follows the same journal protocol as an effect tool: {@code effect_pending}
 * before the call, {@code effect_done} after it, so a process that dies in between is never silently allowed to repeat a payment.
 */
final class TaskRunner {

    static final String PENDING = "effect_pending";
    static final String DONE = "effect_done";
    static final String FAILED = "effect_failed";
    private static final String RUN_UID_KEY = "#effect-run-uid";

    private final HarnessExecutor executor;

    TaskRunner(HarnessExecutor executor) {
        this.executor = executor;
    }

    void run(RunStmt stmt) {
        String stepId = executor.currentStep();
        String name = stmt.getTaskName();
        Task task = executor.getTaskRegistry().get(name);
        if (task == null) throw new IllegalStateException("Task not found: " + name); // validated earlier
        String variable = executor.resolveVariableName(stmt.getVariableName());
        RunJournal journal = executor.journal();

        // A resumed run: this step already happened — reuse its recorded result, don't run the task.
        var recorded = journal.get(stepId).filter(e -> !"retry".equals(e.kind())); // "retry": an operator asked for the step to be tried again
        boolean operatorRetry = journal.get(stepId).filter(e -> "retry".equals(e.kind())).isPresent();
        if (recorded.isPresent()) {
            RunJournal.Entry entry = recorded.get();
            if ("failed".equals(entry.kind())) {
                fail(stmt, stepId, String.valueOf(entry.value()), null, false);
            } else {
                executor.setVariable(variable, entry.value());
                replayed(name, entry.value());
            }
            return;
        }

        Map<String, Object> args;
        try {
            args = arguments(stmt);
        } catch (StepFailure bad) {
            fail(stmt, stepId, bad.getMessage(), bad, true);
            return;
        }

        TaskEffect effect = task.effect();
        boolean changes = effect == TaskEffect.CHANGES;
        if (executor.simulating() && changes) {
            // described, not performed: nobody is asked to approve it, and nothing is recorded as done
            Map<String, Object> simulated = Map.of("outcome", "simulated");
            executor.setVariable(variable, simulated);
            Map<String, Object> data = data(name, effect, "simulated", 0);
            data.put("simulated", true);
            executor.trace(TraceEvent.TASK_END, null, name + " → simulated (not run)", data);
            return;
        }

        if (task.requiresApproval(args) && !executor.approvals().approveTask(name, args)) {
            fail(stmt, stepId, "a person did not approve running " + name, null, true);
            return;
        }

        EffectPolicy policy = task.policy();
        boolean mayRepeat = !changes || policy.idempotent() || policy.onUnknown() == EffectPolicy.OnUnknown.RETRY;
        String effectKey = executor.identityStep() + "#effect:" + name + ":" + CanonicalArgs.hash12(name, args) + "#0";
        String idempotencyKey = "";

        if (changes) {
            // Decide and claim under the journal's lock (so parallel branches can't all slip under a cap), but act on the decision
            // outside it: an on_failure block can take as long as it likes and must not hold other branches up.
            Object reused = null;
            boolean reuse = false;
            String refusal = null;
            boolean unknownOutcome = false;
            synchronized (journal) {
                RunJournal.Entry earlier = journal.get(effectKey).orElse(null);
                if (earlier != null && DONE.equals(earlier.kind()) && !operatorRetry) {
                    // The same effect was done before the run went back or was resumed: not repeated, its result is reused.
                    reuse = true;
                    reused = earlier.value();
                } else if (earlier != null && PENDING.equals(earlier.kind()) && !mayRepeat && !operatorRetry) {
                    unknownOutcome = true;
                    refusal = name + " may already have run: an earlier attempt's outcome is unknown, and it changes things and "
                            + "is not idempotent, so it was not run again. Check whether it happened, then resume the step with a retry.";
                } else if (earlier == null && policy.maxPerRun() > 0 && callsSoFar(journal, name) >= policy.maxPerRun()) {
                    refusal = name + " is limited to " + policy.maxPerRun() + " runs per run, and has used them.";
                } else {
                    journal.put(effectKey, new RunJournal.Entry(PENDING, ""));
                    idempotencyKey = idempotencyKey(journal, effectKey);
                }
            }
            if (reuse) {
                journal.put(stepId, new RunJournal.Entry("task", reused));
                executor.setVariable(variable, reused);
                replayed(name, reused);
                return;
            }
            if (refusal != null) {
                if (unknownOutcome) executor.audit("task_unknown", data(name, effect, "unknown", 0));
                fail(stmt, stepId, refusal, null, true);
                return;
            }
        }

        TaskContext context = TaskContext.of(args, executor.view().getAll(), stepId, idempotencyKey);
        Map<String, Object> startData = data(name, effect, null, 0);
        String shownArgs = executor.maskPii(CanonicalArgs.json(args)); // sorted-key JSON: the same text in every runtime
        startData.put("args", shownArgs);
        startData.put("variable", variable);
        executor.trace(TraceEvent.TASK_START, null, "run " + name, startData);

        int maxAttempts = stmt.getRetryCount() + 1;
        Throwable last = null;
        boolean unknown = false;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            long started = executor.clock().millis();
            try {
                if (attempt > 0 && stmt.getBackoffMillis() > 0) {
                    executor.sleeper().sleep(Duration.ofMillis(stmt.getBackoffMillis() << Math.min(attempt - 1, 10)));
                }
                TaskResult result = stmt.getTimeoutMillis() > 0
                        ? executor.callWithTimeout(() -> task.run(context), stmt.getTimeoutMillis(), "Task " + name)
                        : task.run(context);
                if (result == null) throw new IllegalStateException(name + " returned no result; a task must return a TaskResult");
                Map<String, Object> value = result.toMap();
                if (changes) journal.put(effectKey, new RunJournal.Entry(DONE, value));
                journal.put(stepId, new RunJournal.Entry("task", value));
                executor.setVariable(variable, value);
                Map<String, Object> end = data(name, effect, result.outcome(), executor.clock().millis() - started);
                executor.trace(TraceEvent.TASK_END, null, name + " → " + result.outcome(), end);
                Map<String, Object> audit = new LinkedHashMap<>(end);
                audit.put("args", shownArgs);
                executor.audit("task_run", audit);
                return;
            } catch (TaskNotPerformed notDone) {
                last = notDone; // provably did nothing: the step can be tried again
                if (changes) journal.put(effectKey, new RunJournal.Entry(FAILED, String.valueOf(notDone.getMessage())));
                unknown = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while running task " + name, e);
            } catch (io.github.llm4j.agent.AgentInterrupt pause) { // includes RunSuspended: waiting for a person is not a failure
                throw pause;
            } catch (Exception e) {
                last = e;
                unknown = changes; // it may have reached the other side
                if (!mayRepeat) break; // and running it again could do it twice
            }
        }
        String message = last == null || last.getMessage() == null ? "unknown error" : last.getMessage();
        if (unknown) {
            message = name + " failed and its outcome is unknown" + (mayRepeat ? "" : " (it changes things and is not idempotent, so it was not run again)")
                    + ": " + message;
        }
        Map<String, Object> failed = data(name, effect, unknown ? "unknown" : "failed", 0);
        failed.put("error", message);
        executor.trace(TraceEvent.TASK_END, null, name + " failed: " + message, failed);
        fail(stmt, stepId, message, last, true);
    }

    /** The step failed: run {@code on_failure} (recording the failure so a resume goes straight there), or fail the run. */
    private void fail(RunStmt stmt, String stepId, String message, Throwable cause, boolean record) {
        if (stmt.getOnFailure().isEmpty()) {
            throw new RuntimeException("Task " + stmt.getTaskName() + " failed: " + message, cause);
        }
        if (record) executor.journal().put(stepId, new RunJournal.Entry("failed", message));
        executor.runFailureHandler(stmt.getOnFailure(), message);
    }

    private void replayed(String name, Object value) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("task", name);
        if (value instanceof Map<?, ?> m && m.get("outcome") != null) d.put("outcome", String.valueOf(m.get("outcome")));
        executor.trace(TraceEvent.TASK_REPLAYED, null, name + ": reused the recorded result", d);
    }

    private Map<String, Object> data(String name, TaskEffect effect, String outcome, long millis) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("task", name);
        d.put("effect", effect.name().toLowerCase(java.util.Locale.ROOT));
        d.put("step", executor.currentStep());
        if (outcome != null) d.put("outcome", outcome);
        if (millis > 0) d.put("millis", millis);
        return d;
    }

    /** The arguments, resolved: a variable keeps its type; an argument that names a variable with no value fails the step (closed, not empty). */
    private Map<String, Object> arguments(RunStmt stmt) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (RunStmt.Arg a : stmt.getArgs()) {
            switch (a.kind()) {
                case STRING -> out.put(a.name(), executor.resolve(a.text()));
                case BOOLEAN -> out.put(a.name(), Boolean.valueOf(a.text()));
                case NUMBER -> out.put(a.name(), number(a.text()));
                case REFERENCE -> {
                    // A variable that was never set reads as "" everywhere else in Loom; for a deterministic step that would fail open.
                    String head = a.text().contains(".") ? a.text().substring(0, a.text().indexOf('.')) : a.text();
                    Object value = executor.view().getAll().containsKey(head) ? ConditionEvaluator.resolvePath(a.text(), executor.view()) : null;
                    if (value == null) {
                        throw new StepFailure("argument " + a.name() + " refers to " + a.text() + ", which has no value, so " + stmt.getTaskName() + " was not run", null);
                    }
                    out.put(a.name(), value);
                }
            }
        }
        return out;
    }

    private static Number number(String text) {
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException notInteger) {
            return Double.valueOf(text);
        }
    }

    private static long callsSoFar(RunJournal journal, String task) {
        String marker = "#effect:" + task + ":";
        return journal.all().entrySet().stream()
                .filter(e -> e.getKey().contains(marker))
                .filter(e -> PENDING.equals(e.getValue().kind()) || DONE.equals(e.getValue().kind()))
                .count();
    }

    /** Stable across a retry and a resume of this step, and unique to this run, so a payments API can deduplicate. */
    private static String idempotencyKey(RunJournal journal, String effectKey) {
        String uid = journal.get(RUN_UID_KEY).map(e -> String.valueOf(e.value())).orElseGet(() -> {
            String fresh = java.util.UUID.randomUUID().toString();
            journal.put(RUN_UID_KEY, new RunJournal.Entry("effect_run", fresh));
            return fresh;
        });
        return CanonicalArgs.sha256Hex(uid + effectKey).substring(0, 32);
    }
}
