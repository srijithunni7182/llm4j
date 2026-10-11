package io.github.llm4j.loom.inspect.sink;

import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.execution.TraceListener;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The trace listener of an observed run. It is on the run's own thread, so it does almost nothing: it puts the event in a bounded
 * queue and returns. A separate thread hands the events, in order, to the consumer. A full queue never blocks the run: the oldest
 * event is dropped and counted. A failing consumer never reaches the run either: it is logged (at most once a minute) and the
 * next event is delivered.
 */
public final class EventSink implements TraceListener, AutoCloseable {

    private static final Logger LOG = Logger.getLogger(EventSink.class.getName());
    private static final long LOG_EVERY_MILLIS = 60_000;

    private final ArrayBlockingQueue<TraceEvent> queue;
    private final Consumer<TraceEvent> consumer;
    private final Thread pump;
    private final AtomicLong enqueued = new AtomicLong();
    private final AtomicLong finished = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean closed;
    private volatile long lastLogged;

    public EventSink(String name, int capacity, Consumer<TraceEvent> consumer) {
        this.queue = new ArrayBlockingQueue<>(Math.max(1, capacity));
        this.consumer = consumer;
        this.pump = new Thread(this::pump, "loom-inspect-" + name);
        this.pump.setDaemon(true);
        this.pump.start();
    }

    @Override
    public void onEvent(TraceEvent event) {
        if (closed) return;
        while (!queue.offer(event)) {
            if (queue.poll() != null) dropped.incrementAndGet();
        }
        enqueued.incrementAndGet();
    }

    /** Events dropped because the queue was full. */
    public long dropped() {
        return dropped.get();
    }

    /** Waits until every event accepted so far has been handed to the consumer (or dropped); false on timeout. For tests and for closing. */
    public boolean awaitIdle(Duration timeout) {
        long until = System.nanoTime() + timeout.toNanos();
        while (finished.get() + dropped.get() < enqueued.get()) {
            if (System.nanoTime() > until) return false;
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** Delivers what is queued (for up to two seconds), then stops the thread. */
    @Override
    public void close() {
        awaitIdle(Duration.ofSeconds(2));
        closed = true;
        pump.interrupt();
        try {
            pump.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void pump() {
        while (!closed) {
            TraceEvent e;
            try {
                e = queue.poll(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                if (closed) return;
                continue;
            }
            if (e == null) continue;
            try {
                consumer.accept(e);
            } catch (RuntimeException ex) {
                long now = System.currentTimeMillis();
                if (now - lastLogged > LOG_EVERY_MILLIS) {
                    lastLogged = now;
                    LOG.log(Level.WARNING, "loom-inspect could not handle a trace event: " + ex, ex);
                }
            } finally {
                finished.incrementAndGet();
            }
        }
    }
}
