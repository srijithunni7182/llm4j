package io.github.llm4j.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The remote control for one agent run. Create it, pass it to {@link ReActAgent#run(String, RunHandle)}, and keep a reference: any other
 * thread (a UI, a request handler, a chat listener) can then {@link #steer steer}, {@link #pause pause}, {@link #resume resume} or
 * {@link #cancel cancel} the run while {@code run} blocks the calling thread.
 *
 * <p>The loop looks at the handle at its checkpoints: before each model call, and between the tool calls of one turn. So a command
 * takes effect at the next checkpoint, never in the middle of a model call or a tool that is already running.
 *
 * <ul>
 *   <li>{@code steer(text)}: the text is shown to the model, as a message from the user, before its next model call. Several
 *       messages arrive in the order they were sent.
 *   <li>{@code pause()}: the loop stops at the next checkpoint and waits, holding its thread, until {@code resume()} or {@code cancel()}.
 *       Steering sent while paused is kept and delivered on resume.
 *   <li>{@code cancel()}: the run ends at the next checkpoint with a result that is not completed and {@link AgentResult#isCancelled()}.
 *       A cancel cannot be undone, and it holds even if it arrives before the run starts: that run ends cancelled without calling the model.
 * </ul>
 *
 * <p>A handle belongs to one run at a time; using it for a second run while the first is still going is an error. It is safe to call
 * from any thread.
 */
public final class RunHandle {

    /** Where a run is. */
    public enum State {
        /** Not yet passed to a run, or between runs. */
        IDLE,
        RUNNING,
        PAUSED,
        CANCELLED,
        /** The last run ended on its own. */
        FINISHED
    }

    private final Object lock = new Object();
    private final List<String> steering = new ArrayList<>();
    private State state = State.IDLE;
    private boolean bound;
    private boolean cancelRequested;
    private boolean pauseRequested;
    private Runnable interruptListener;

    /** Queues a message for the model, delivered before its next model call. Blank messages are ignored. */
    public void steer(String message) {
        Objects.requireNonNull(message, "message cannot be null");
        if (message.isBlank()) return;
        synchronized (lock) {
            steering.add(message.strip());
            lock.notifyAll();
        }
        interrupt();
    }

    /** Asks the run to wait at its next checkpoint until {@link #resume()}. Does nothing once cancelled. */
    public void pause() {
        synchronized (lock) {
            if (cancelRequested) return;
            pauseRequested = true;
        }
    }

    /** Lets a paused run carry on. Does nothing if it is not paused. */
    public void resume() {
        synchronized (lock) {
            pauseRequested = false;
            lock.notifyAll();
        }
    }

    /** Ends the run at its next checkpoint, also if it is paused. */
    public void cancel() {
        synchronized (lock) {
            cancelRequested = true;
            pauseRequested = false;
            lock.notifyAll();
        }
        interrupt();
    }

    public State state() {
        synchronized (lock) {
            return state;
        }
    }

    public boolean isCancelRequested() {
        synchronized (lock) {
            return cancelRequested;
        }
    }

    // ---- the loop's side ----

    /** Called when a run starts. A handle cancelled before this point is still bound: the run then ends cancelled at its first checkpoint, before any model call. */
    void bind() {
        synchronized (lock) {
            if (bound) throw new IllegalStateException("this RunHandle is already controlling a run");
            bound = true;
            state = State.RUNNING;
        }
    }

    /** Called when the run ends, however it ended. */
    void release() {
        synchronized (lock) {
            bound = false;
            state = cancelRequested ? State.CANCELLED : State.FINISHED;
        }
    }

    /** The loop's checkpoint: waits while paused (telling {@code listener} when it stops and starts), and says whether to go on. */
    boolean proceed(Runnable onPause, Runnable onResume) {
        synchronized (lock) {
            if (cancelRequested) return false;
            if (!pauseRequested) return true;
            state = State.PAUSED;
        }
        onPause.run();
        synchronized (lock) {
            boolean interrupted = false;
            while (pauseRequested && !cancelRequested) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                    cancelRequested = true; // an interrupted thread should not go on waiting
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            if (cancelRequested) return false;
            state = State.RUNNING;
        }
        onResume.run();
        return true;
    }

    /**
     * Sets what to do the moment a steer or cancel arrives (the loop uses it to close a model call that is streaming, so a message does
     * not wait for the next chunk). Runs at once if one is already waiting. Pass null to clear it.
     */
    void onInterrupt(Runnable listener) {
        synchronized (lock) {
            interruptListener = listener;
        }
        if (listener != null && interruptRequested()) interrupt();
    }

    /** Whether a steer or cancel is waiting. */
    boolean interruptRequested() {
        synchronized (lock) {
            return cancelRequested || !steering.isEmpty();
        }
    }

    private void interrupt() {
        Runnable listener;
        synchronized (lock) {
            listener = interruptListener;
        }
        if (listener == null) return;
        try {
            listener.run();
        } catch (RuntimeException ignored) {
            // closing a stream that is already over is not a failure
        }
    }

    /** The steering sent since the last call, oldest first. */
    List<String> drainSteering() {
        synchronized (lock) {
            if (steering.isEmpty()) return List.of();
            List<String> out = new ArrayList<>(steering);
            steering.clear();
            return out;
        }
    }
}
