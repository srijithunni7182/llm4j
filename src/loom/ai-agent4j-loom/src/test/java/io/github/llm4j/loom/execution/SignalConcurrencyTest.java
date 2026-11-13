package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.loom.children.ChildHarness;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Guard G19 of phase 34 (D12): one run of a child at a time, and no lost wake-up, with real threads.
 * A parent sends signals as fast as it can while a child that suspends and is woken again reads
 * them; every message is received exactly once, in order, and the child never runs twice at once.
 * The controlled scheduler cannot reach this interleaving, so it is the named real-thread case.
 */
@Tag("slow")
class SignalConcurrencyTest {

    private static final int MESSAGES = 12; // under the limit of 16 unread, so the race is about delivery and not about a full mailbox

    @org.junit.jupiter.api.RepeatedTest(25)
    void everyMessageIsReceivedOnceInOrderAndTheChildNeverRunsTwiceAtOnce() throws Exception {
        ChildHarness h = new ChildHarness();
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();
        h.task(
                "Track",
                ctx -> {
                    int now = inside.incrementAndGet();
                    maxInside.accumulateAndGet(now, Math::max);
                    Thread.yield();
                    inside.decrementAndGet();
                    return TaskResult.value("tracked");
                });
        StringBuilder waits = new StringBuilder();
        for (int i = 0; i < MESSAGES; i++) waits.append("run Track() -> t").append(i).append("\nwait signal \"n\" timeout 10m -> m").append(i).append("\n");
        StringBuilder sends = new StringBuilder();
        for (int i = 0; i < MESSAGES; i++) sends.append("notify work \"n\" \"").append(i).append("\"\n");
        StringBuilder all = new StringBuilder("return \"");
        for (int i = 0; i < MESSAGES; i++) all.append("{m").append(i).append("},");
        all.append("\"");
        HarnessExecutor parent =
                h.parent(
                        "workflow Child(x) {\n" + waits + all + "\n}\n"
                                + "workflow Main(q) {\nstart Child(x = q) budget { tokens: 10 } -> work\n" + sends + "wait work timeout 5m -> out\nset seen = work.status\n}\n",
                        ChildScheduler.threads());
        Thread t = new Thread(() -> parent.executeWorkflow("Main", Map.of("q", "x")));
        t.setDaemon(true);
        t.start();
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (t.isAlive() && System.nanoTime() < end) t.join(20); // the virtual clock stays still: see the next test

        assertThat(t.isAlive()).as("the parent finished").isFalse();
        assertThat(parent.getContext().getVariable("seen")).isEqualTo("completed");
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < MESSAGES; i++) expected.append(i).append(",");
        assertThat(String.valueOf(parent.getContext().getVariable("out"))).contains(expected.toString());
        assertThat(maxInside.get()).as("never two runs of the child at once").isEqualTo(1);
        assertThat(Mailbox.pending(h.onlyChild(), Mailbox.Direction.IN, "n")).as("nothing is left unread").isZero();
    }

    @org.junit.jupiter.api.RepeatedTest(25)
    void manyNotifiesRacingAWakeNeverStartASecondRunOfTheSameChild() throws Exception {
        ChildHarness h = new ChildHarness();
        CountDownLatch go = new CountDownLatch(1);
        HarnessExecutor parent =
                h.parent(
                        "workflow Child(x) { wait signal \"a\" timeout 10m -> v  wait signal \"a\" timeout 10m -> w  return \"{v}{w}\" }\n"
                                + "workflow Main(q) {\nstart Child(x = q) budget { tokens: 10 } -> work\nwait work timeout 5m -> out\nset seen = work.status\n}\n",
                        ChildScheduler.threads());
        Thread run = new Thread(() -> parent.executeWorkflow("Main", Map.of("q", "x")));
        run.setDaemon(true);
        run.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (parent.startedChildren().isEmpty() && System.nanoTime() < deadline) Thread.onSpinWait();
        var view = parent.startedChildren().get(0);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        for (int i = 0; i < 4; i++) {
            int id = i;
            pool.submit(() -> {
                try {
                    go.await();
                    parent.signalChild(view.handle(), "a", "x" + id, false, "race/" + id);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        // the virtual clock stays still: nothing here needs time to pass, and moving it against real time would let
        // the parent's own timeout fire on a slow machine
        while (run.isAlive() && System.nanoTime() < end) run.join(20);

        assertThat(run.isAlive()).isFalse();
        // four senders, two reads: the child consumed the first two of the mailbox, in its order, and finished;
        // a sender that came after it finished was dropped as a send to a settled child, so how many are left varies
        assertThat(parent.getContext().getVariable("seen")).isEqualTo("completed");
        var sent = Mailbox.all(h.onlyChild(), Mailbox.Direction.IN);
        assertThat(sent).hasSizeGreaterThanOrEqualTo(2).hasSizeLessThanOrEqualTo(4);
        assertThat(String.valueOf(parent.getContext().getVariable("out"))).isEqualTo(sent.get(0).text() + sent.get(1).text());
        assertThat(Mailbox.pending(h.onlyChild(), Mailbox.Direction.IN, "a")).isEqualTo(sent.size() - 2);
        assertThat(h.attempts.size()).as("each wake is one run; never more runs than reads plus the first").isLessThanOrEqualTo(3);
    }
}
