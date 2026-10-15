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

    /** What the loop's checkpoint decides. */
    enum Signal { GO, CANCEL, WRAP_UP }

    /** One message sent with {@link #steer}, and who sent it. */
    public record Steer(String text, String origin) { }

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
    private final List<Steer> steers = new ArrayList<>();
    private boolean wrapUpRequested;
    private String stopReason;
    private String stopOrigin;
    private State state = State.IDLE;
    private boolean bound;
    private boolean cancelRequested;
    private boolean pauseRequested;
    private Runnable interruptListener;

    /** Queues a message for the model, delivered before its next model call. Blank messages are ignored. */
    public void steer(String message) {
        steer(message, null);
    }

    /** As {@link #steer(String)}, recording who sent it (see {@link #steers()}). */
    public void steer(String message, String origin) {
        Objects.requireNonNull(message, "message cannot be null");
        if (message.isBlank()) return;
        synchronized (lock) {
            steering.add(message.strip());
            steers.add(new Steer(message.strip(), origin));
            lock.notifyAll();
        }
        interrupt();
    }

    /** Every message sent to this handle so far, oldest first, whether or not the run has read it yet. */
    public List<Steer> steers() {
        synchronized (lock) {
            return List.copyOf(steers);
        }
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
        cancel(null, null);
    }

    /** As {@link #cancel()}, with the reason it is cancelled. */
    public void cancel(String reason) {
        cancel(reason, null);
    }

    /** As {@link #cancel()}, with why and by whom; both go into the run's {@link StopReport}. The first cancel's reason is kept. */
    public void cancel(String reason, String origin) {
        synchronized (lock) {
            if (!cancelRequested) {
                stopReason = reason;
                stopOrigin = origin;
            }
            cancelRequested = true;
            pauseRequested = false;
            lock.notifyAll();
        }
        interrupt();
    }

    /**
     * Asks the run to finish now with what it has: at its next checkpoint (or at once, if a streaming model call is in progress) it makes one last
     * model call with tools turned off and returns that answer, with a {@link StopReport} of kind {@code WRAP_UP}. A cancel overrides it.
     */
    public void wrapUp() {
        wrapUp(null, null);
    }

    /** As {@link #wrapUp()}, with why and by whom. */
    public void wrapUp(String reason, String origin) {
        synchronized (lock) {
            if (cancelRequested || wrapUpRequested) return;
            wrapUpRequested = true;
            stopReason = reason;
            stopOrigin = origin;
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
    Signal proceed(Runnable onPause, Runnable onResume) {
        synchronized (lock) {
            if (cancelRequested) return Signal.CANCEL;
            if (wrapUpRequested) return Signal.WRAP_UP;
            if (!pauseRequested) return Signal.GO;
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
            if (cancelRequested) return Signal.CANCEL;
            state = State.RUNNING;
        }
        onResume.run();
        synchronized (lock) {
            return wrapUpRequested ? Signal.WRAP_UP : Signal.GO;
        }
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
            return cancelRequested || wrapUpRequested || !steering.isEmpty();
        }
    }

    /** The reason given to the first cancel or wrap-up, or null. */
    String stopReason() {
        synchronized (lock) {
            return stopReason;
        }
    }

    String stopOrigin() {
        synchronized (lock) {
            return stopOrigin;
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
