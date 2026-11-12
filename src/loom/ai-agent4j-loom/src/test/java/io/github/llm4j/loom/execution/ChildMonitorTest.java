package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * The launcher-level monitor (phase 34a, D12 step 1): a parent's {@code wait} sleeps on it and a
 * child's change of state wakes it. Written before the monitor.
 */
class ChildMonitorTest {

    @Test
    void aChangeThatHappenedBeforeTheWaitIsNotLost() throws Exception {
        ChildMonitor monitor = new ChildMonitor();
        long seen = monitor.version();
        monitor.changed(); // arrives between reading the state and going to sleep

        assertThat(monitor.awaitChange(seen, 10_000)).isTrue(); // returns at once, no 10 s wait
    }

    @Test
    void awaitingWithNoChangeEndsAtTheLimitAndSaysSo() throws Exception {
        ChildMonitor monitor = new ChildMonitor();

        assertThat(monitor.awaitChange(monitor.version(), 1)).isFalse();
    }

    @Test
    void aChangeWakesASleepingWaiter() throws Exception {
        ChildMonitor monitor = new ChildMonitor();
        CountDownLatch asleep = new CountDownLatch(1);
        AtomicBoolean woken = new AtomicBoolean();
        long seen = monitor.version();
        Thread waiter =
                new Thread(
                        () -> {
                            try {
                                asleep.countDown();
                                woken.set(monitor.awaitChange(seen, 10_000));
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        });
        waiter.start();
        assertThat(asleep.await(5, TimeUnit.SECONDS)).isTrue();

        monitor.changed();
        waiter.join(5_000);

        assertThat(waiter.isAlive()).isFalse();
        assertThat(woken).isTrue();
    }

    @Test
    void everyChangeAdvancesTheVersion() {
        ChildMonitor monitor = new ChildMonitor();
        long before = monitor.version();
        monitor.changed();
        monitor.changed();

        assertThat(monitor.version()).isEqualTo(before + 2);
    }
}
