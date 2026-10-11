package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.runtime.RunStopped;
import io.github.llm4j.loom.runtime.RunSuspended;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * What an observer may do to a run: pause it, resume it, suspend it, stop it. The run looks at this <b>between steps</b>, before it
 * starts the next statement. A step already running (an agent in the middle of its reasoning) is not interrupted, because what it has
 * begun is already being paid for; the control takes effect when that step finishes. So:
 *
 * <ul>
 *   <li><b>pause</b>: the run holds before its next step, keeping its thread, until {@link Action#RESUME}. A held run can still be
 *       stopped or suspended.</li>
 *   <li><b>suspend</b>: the run ends cleanly at the next step as {@link RunSuspended} ({@code OPERATOR}); with a journal it can be
 *       resumed later by hand, and nothing already done is repeated.</li>
 *   <li><b>stop</b>: the run ends at the next step as {@link RunStopped}; nothing is lost, and it too can be resumed.</li>
 * </ul>
 *
 * Every change is reported to listeners (the executor turns it into a {@code control} trace event), so an observer sees a request as
 * queued until the run actually reaches a step.
 */
public final class RunControl {

    public enum Action { PAUSE, RESUME, SUSPEND, STOP }

    /** One change: {@code requested} (asked, not yet in effect), {@code held} (the run is waiting), {@code resumed}, {@code applied} (a stop or suspend took effect) or {@code cancelled}. */
    public record Event(Action action, String state, String by, String step) { }

    /** What is true now. */
    public record State(boolean paused, boolean held, Action pending, String by) { }

    private final List<Consumer<Event>> listeners = new CopyOnWriteArrayList<>();
    private boolean paused;
    private boolean held;
    private Action pending;
    private String pendingBy;
    private String pausedBy;

    public void addListener(Consumer<Event> listener) {
        listeners.add(listener);
    }

    /** Asks for {@code action}, on behalf of {@code by} (for the record). */
    public void request(Action action, String by) {
        Event e;
        synchronized (this) {
            switch (action) {
                case PAUSE -> {
                    if (paused) return;
                    paused = true;
                    pausedBy = by;
                    e = new Event(action, "requested", by, null);
                }
                case RESUME -> {
                    if (!paused && pending == null) return;
                    boolean wasHeld = held;
                    paused = false;
                    if (pending != null) {
                        Action was = pending;
                        pending = null;
                        e = new Event(was, "cancelled", by, null);
                    } else {
                        e = new Event(Action.PAUSE, wasHeld ? "resumed" : "cancelled", by, null); // what ends is the pause
                    }
                    notifyAll();
                }
                case SUSPEND, STOP -> {
                    if (pending == action) return;
                    pending = action;
                    pendingBy = by;
                    e = new Event(action, "requested", by, null);
                    notifyAll(); // a held run wakes to act on it
                }
                default -> throw new IllegalArgumentException(String.valueOf(action));
            }
        }
        emit(e);
    }

    public synchronized State state() {
        return new State(paused, held, pending, paused ? pausedBy : pendingBy);
    }

    /** A new top-level run begins: a stop or suspend asked for an earlier run is not meant for this one. A pause stays. */
    synchronized void reset() {
        pending = null;
    }

    /**
     * Called by the run before each step. Returns when the step may go ahead; throws {@link RunStopped} or {@link RunSuspended} when the
     * run was told to end; blocks while paused.
     */
    void checkpoint(String step) {
        while (true) {
            Event toEmit = null;
            Action act = null;
            String by = null;
            synchronized (this) {
                if (pending == null && !paused) {
                    held = false;
                    return;
                }
                if (pending != null) {
                    // stop and suspend stay pending, so every branch of a parallel step ends the same way; reset() clears them for the next run
                    act = pending;
                    by = pendingBy;
                    held = false;
                    toEmit = new Event(act, "applied", by, step);
                } else if (!held) {
                    held = true;
                    toEmit = new Event(Action.PAUSE, "held", pausedBy, step);
                } else {
                    try {
                        wait(250);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RunStopped("interrupted at " + step);
                    }
                    continue;
                }
            }
            emit(toEmit);
            if (act == Action.STOP) throw new RunStopped("operator stop at " + step + " (asked by " + by + ")");
            if (act == Action.SUSPEND) throw RunSuspended.byOperator(step, by);
        }
    }

    private void emit(Event e) {
        for (Consumer<Event> l : listeners) {
            try {
                l.accept(e);
            } catch (RuntimeException ignored) {
                // an observer must never break the run
            }
        }
    }
}
