package io.github.llm4j.spring;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;

/**
 * Runs workflows on the chosen executor and refuses a run beyond {@code
 * llm4j.loom.max-concurrent-runs}.
 */
final class HostRunner implements Executor, AutoCloseable {

    private final Executor delegate;
    private final Semaphore permits;
    private final int max;
    private final boolean owned;

    HostRunner(Executor delegate, int max, boolean owned) {
        this.delegate = delegate;
        this.max = max;
        this.permits = new Semaphore(max);
        this.owned = owned;
    }

    @Override
    public void execute(Runnable task) {
        if (!permits.tryAcquire()) throw new TooManyRuns(max);
        try {
            delegate.execute(
                    () -> {
                        try {
                            task.run();
                        } finally {
                            permits.release();
                        }
                    });
        } catch (RuntimeException rejected) {
            permits.release();
            throw rejected;
        }
    }

    @Override
    public void close() {
        if (owned && delegate instanceof ExecutorService s) s.shutdownNow();
    }
}
