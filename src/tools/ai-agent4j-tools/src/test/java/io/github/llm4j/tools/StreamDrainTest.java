package io.github.llm4j.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Reading a program's output blocks for as long as the program runs, so it must not take a thread
 * of the common pool: with that pool busy, a drain still finishes (review of #122, finding 8).
 */
class StreamDrainTest {

    @Test
    void aDrainFinishesWhileTheCommonPoolIsBusy() throws Exception {
        int threads = ForkJoinPool.getCommonPoolParallelism();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch busy = new CountDownLatch(threads);
        List<CompletableFuture<Void>> blockers = new ArrayList<>();
        for (int i = 0; i < threads; i++)
            blockers.add(
                    CompletableFuture.runAsync(
                            () -> {
                                busy.countDown();
                                try {
                                    release.await();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            }));
        try {
            assertThat(busy.await(5, TimeUnit.SECONDS)).as("every common-pool thread is taken").isTrue();
            byte[] printed = "hello".getBytes(StandardCharsets.UTF_8);
            CompletableFuture<HeadAndTail> drained =
                    ProcessSandbox.drain(new ByteArrayInputStream(printed)::transferTo, 1024);
            assertThat(drained.get(5, TimeUnit.SECONDS).text()).isEqualTo("hello");
        } finally {
            release.countDown();
            for (CompletableFuture<Void> b : blockers) b.join();
        }
    }

    @Test
    void aReadThatFailsIsReportedNotLeftWaiting() {
        CompletableFuture<HeadAndTail> drained =
                ProcessSandbox.drain(
                        out -> {
                            throw new IllegalStateException("broken");
                        },
                        1024);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> drained.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(IllegalStateException.class);
    }
}
