package io.github.llm4j.loom.execution;

/**
 * The launcher's monitor (phase 34a, D12 step 1): a parent's {@code wait} sleeps on it, and every
 * change in a child's state wakes it. It is a version counter with a condition, so a change that
 * happens between reading the children and going to sleep is not lost: the waiter passes the
 * version it saw, and a newer one returns at once.
 */
final class ChildMonitor {

    private long version;

    /** The current version: read it before looking at the children, pass it to {@link #awaitChange}. */
    synchronized long version() {
        return version;
    }

    /** Something about a child changed: it ended, paused, was relaunched, or a signal arrived. */
    synchronized void changed() {
        version++;
        notifyAll();
    }

    /**
     * Sleeps until the version is newer than {@code seen} or {@code maxMillis} have passed, and says
     * whether it changed.
     */
    synchronized boolean awaitChange(long seen, long maxMillis) throws InterruptedException {
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(maxMillis);
        while (version == seen) {
            long left = end - System.nanoTime();
            if (left <= 0) return false;
            java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(this, left);
        }
        return true;
    }
}
