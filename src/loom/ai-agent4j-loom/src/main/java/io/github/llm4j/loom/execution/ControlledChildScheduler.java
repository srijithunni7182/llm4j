package io.github.llm4j.loom.execution;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A scheduler for tests and for {@code weave test}: a child's body is queued and runs, to its end,
 * on the thread that waits for it (or on {@link #runNext()}), one at a time and in the order they
 * were started. No thread races, so a test of a supervisor is the same every time.
 */
public final class ControlledChildScheduler implements ChildScheduler {

    private final Deque<Queued> queue = new ArrayDeque<>();

    private final class Queued implements Task {
        private final Runnable body;
        private boolean done;

        Queued(Runnable body) {
            this.body = body;
        }

        void run() {
            try {
                body.run();
            } finally {
                done = true;
            }
        }

        @Override
        public boolean isDone() {
            return done;
        }

        @Override
        public boolean await(long timeoutMillis) {
            while (!done && !queue.isEmpty()) runNext();
            return done;
        }
    }

    private java.util.function.LongConsumer onIdle;

    /**
     * Sets what happens when a parent waits and nothing can run: a test advances its clock by the
     * milliseconds given and lets the supervisor look, so a wait with a timeout is a test of virtual
     * time and nothing sleeps.
     */
    public void onIdle(java.util.function.LongConsumer advance) {
        this.onIdle = advance;
    }

    @Override
    public boolean idle(long maxMillis) {
        if (onIdle == null || maxMillis <= 0) return false;
        onIdle.accept(maxMillis);
        return true;
    }

    @Override
    public Task submit(String name, Runnable body) {
        Queued task = new Queued(body);
        queue.add(task);
        return task;
    }

    /** Runs the oldest queued child to its end; false when none is queued. */
    public boolean runNext() {
        Queued next = queue.poll();
        if (next == null) return false;
        next.run();
        return true;
    }

    /** How many children are queued and have not run. */
    public int queued() {
        return queue.size();
    }
}
