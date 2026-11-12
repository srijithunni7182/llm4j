package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.runtime.RunStopped;
import io.github.llm4j.loom.runtime.RunSuspended;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A workflow running on its own thread, with the controls an embedding application needs: steer,
 * wrap up, cancel (with the outcome it names, or sending the run back to a checkpoint), pause and
 * resume. It is also registered with {@link LiveRuns}, so a console or chat in the same process can
 * reach it.
 *
 * <pre>{@code
 * LoomRun run = LoomRun.start(executor, "Digest", Map.of("topic", "AI agents"));
 * run.steer("Researcher", "focus on the EU market");
 * run.wrapUp("Writer");
 * run.cancel("Writer", Outcome.USE_PARTIAL, "that is enough");
 * run.cancel("Writer", Outcome.rewindTo("collected"), "the sources were stale");
 * run.await(Duration.ofMinutes(5));
 * }</pre>
 *
 * Every call returns how many running agents took it; 0 means none matched or it was refused (the
 * reason is in the run's trace). The executor must be initialized. The application decides who may
 * call these; Loom records the {@code origin} given to {@link #start}.
 */
public final class LoomRun {

    /** Where the run is. */
    public enum Status {
        RUNNING,
        COMPLETED,
        SUSPENDED,
        STOPPED,
        FAILED
    }

    private final HarnessExecutor executor;
    private final String origin;
    private final CountDownLatch done = new CountDownLatch(1);
    private volatile Status status = Status.RUNNING;
    private volatile Throwable failure;
    private final Runnable body;
    private final io.github.llm4j.loom.runtime.ChildHandle attachedTo;

    private LoomRun(
            HarnessExecutor executor,
            String label,
            String workflow,
            Map<String, String> inputs,
            String origin,
            io.github.llm4j.secret.InvokerCredentials invoker,
            java.util.function.Consumer<LoomRun> onDone) {
        this.executor = executor;
        this.origin = origin;
        this.attachedTo = null;
        this.body =
                () -> {
                    try (AutoCloseable registered = LiveRuns.shared().register(label, executor)) {
                        executor.executeWorkflow(workflow, inputs, invoker);
                        status = Status.COMPLETED;
                    } catch (RunSuspended suspended) {
                        status = Status.SUSPENDED;
                        failure = suspended;
                    } catch (RunStopped stopped) {
                        status = Status.STOPPED;
                        failure = stopped;
                    } catch (Throwable t) {
                        status = Status.FAILED;
                        failure = t;
                    } finally {
                        try {
                            if (onDone != null) onDone.accept(this);
                        } finally {
                            done.countDown();
                        }
                    }
                };
    }

    /** A view of a child run started by a script: its state is the handle's, its controls are the executor's. */
    private LoomRun(HarnessExecutor executor, String origin, io.github.llm4j.loom.runtime.ChildHandle handle) {
        this.executor = executor;
        this.origin = origin;
        this.attachedTo = handle;
        this.body = () -> {};
    }

    /**
     * The runs the script of this one has started and not yet ended, each a run of its own with
     * the same controls ({@code steer}, {@code wrapUp}, {@code cancel}, {@code pause}, {@code
     * resume}, {@link #await}). Steering still needs a {@code steerable} agent and is journaled
     * with its origin (decision D18).
     */
    public List<LoomRun> children() {
        List<LoomRun> out = new java.util.ArrayList<>();
        for (HarnessExecutor.ChildView c : executor.startedChildren())
            out.add(new LoomRun(c.executor(), origin, c.handle()));
        return out;
    }

    /**
     * Starts the workflow; its label is the workflow's name and the origin of what the application
     * does to it is {@code "app"}.
     */
    public static LoomRun start(
            HarnessExecutor executor, String workflow, Map<String, String> inputs) {
        return start(executor, workflow, workflow, inputs, "app");
    }

    /**
     * @param label how a person names this run to a console or chat ({@code #label}); @param origin
     *     who the journal and audit say acted
     */
    public static LoomRun start(
            HarnessExecutor executor,
            String label,
            String workflow,
            Map<String, String> inputs,
            String origin) {
        LoomRun run = new LoomRun(executor, label, workflow, inputs, origin, null, null);
        Thread thread = new Thread(run.body, "loom-run-" + label);
        thread.setDaemon(true);
        thread.start();
        return run;
    }

    /**
     * Starts the workflow for the person {@code invoker} stands for (may be null), on a thread the
     * application provides, and calls {@code onDone} with the finished run, whatever the outcome,
     * before {@link #await} returns. {@code runner} must run the task or throw; if it throws,
     * nothing has started.
     */
    public static LoomRun start(
            HarnessExecutor executor,
            String label,
            String workflow,
            Map<String, String> inputs,
            String origin,
            io.github.llm4j.secret.InvokerCredentials invoker,
            java.util.concurrent.Executor runner,
            java.util.function.Consumer<LoomRun> onDone) {
        LoomRun run = new LoomRun(executor, label, workflow, inputs, origin, invoker, onDone);
        runner.execute(run.body);
        return run;
    }

    public Status status() {
        if (attachedTo != null)
            return switch (attachedTo.status()) {
                case RUNNING -> Status.RUNNING;
                case SLEEPING, WAITING -> Status.SUSPENDED;
                case COMPLETED -> Status.COMPLETED;
                case FAILED -> Status.FAILED;
                case STOPPED, CANCELLED, TIMED_OUT -> Status.STOPPED;
            };
        return status;
    }

    /** Why the run did not complete (the suspension, the stop, or the error), or null. */
    public Throwable failure() {
        if (attachedTo != null) {
            Object why = attachedTo.fact("failure") != null ? attachedTo.fact("failure") : attachedTo.fact("reason");
            return why == null ? null : new IllegalStateException(String.valueOf(why));
        }
        return failure;
    }

    /** Waits for the run to end; false if it is still going after {@code timeout}. */
    public boolean await(Duration timeout) throws InterruptedException {
        if (attachedTo != null) {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (!attachedTo.status().isSettled()) {
                if (System.nanoTime() >= deadline) return false;
                Thread.sleep(10);
            }
            return true;
        }
        return done.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** The agents working now: step id to agent name. */
    public Map<String, String> runningAgents() {
        return executor.runningAgents();
    }

    /** The executor, to read the run's variables, trace and journal. */
    public HarnessExecutor executor() {
        return executor;
    }

    /**
     * Sends a message to the agent(s) named (an agent name, a step id, or {@code *}). The agent
     * must be declared {@code steerable}.
     */
    public int steer(String agent, String text) {
        return executor.steer(agent, text, origin);
    }

    public int wrapUp(String agent) {
        return wrapUp(agent, "asked to wrap up");
    }

    public int wrapUp(String agent, String reason) {
        return executor.wrapUp(agent, reason, origin);
    }

    /** Cancels with the script deciding what happens next. */
    public int cancel(String agent, String reason) {
        return cancel(agent, Outcome.byTheScript(), reason);
    }

    public int cancel(String agent, Outcome outcome, String reason) {
        if (outcome.checkpoint() != null)
            return executor.cancelBack(agent, outcome.checkpoint(), reason, origin);
        return executor.cancel(agent, reason, origin, outcome.outcome());
    }

    public int pause(String agent) {
        return executor.pauseAgents(agent, origin);
    }

    public int resume(String agent) {
        return executor.resumeAgents(agent, origin);
    }
}
