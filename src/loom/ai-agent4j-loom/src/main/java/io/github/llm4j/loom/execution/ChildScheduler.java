package io.github.llm4j.loom.execution;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;

/**
 * Runs the child runs of a parent (decision D5, D17): in production on daemon threads, in tests on
 * a {@link ControlledChildScheduler} that runs them one at a time when the test says, so nothing
 * races. The executor takes one by injection; it is not a flag in the engine.
 */
public interface ChildScheduler {

    /** A child's run, queued or running. */
    interface Task {
        /** True once the child's body has returned or thrown. */
        boolean isDone();

        /**
         * Blocks until the body is done or {@code timeoutMillis} has passed, whichever is first.
         * Returns true when it is done.
         */
        boolean await(long timeoutMillis) throws InterruptedException;
    }

    /** Starts {@code body}, the whole run of the child named {@code name}. */
    Task submit(String name, Runnable body);

    /** Threads created when the first child starts and never before. */
    static ChildScheduler threads() {
        return new ChildScheduler() {
            private ExecutorService pool;

            @Override
            public synchronized Task submit(String name, Runnable body) {
                if (pool == null)
                    pool =
                            Executors.newCachedThreadPool(
                                    r -> {
                                        Thread t = new Thread(r, "loom-child");
                                        t.setDaemon(true);
                                        return t;
                                    });
                Future<?> future = pool.submit(body);
                return new Task() {
                    @Override
                    public boolean isDone() {
                        return future.isDone();
                    }

                    @Override
                    public boolean await(long timeoutMillis) throws InterruptedException {
                        try {
                            future.get(timeoutMillis, TimeUnit.MILLISECONDS);
                            return true;
                        } catch (TimeoutException e) {
                            return false;
                        } catch (ExecutionException e) {
                            return true; // the body records its own failure on the handle
                        }
                    }
                };
            }
        };
    }
}
