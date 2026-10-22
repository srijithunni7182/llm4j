package io.github.llm4j.loom.inspect.sink;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.TraceEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class EventSinkTest {

    private static TraceEvent ev(int n) {
        return new TraceEvent(TraceEvent.NOTE, null, "W/s0", "e" + n, Map.of(), Instant.EPOCH);
    }

    private static List<String> texts(List<TraceEvent> events) {
        synchronized (events) {
            return events.stream().map(TraceEvent::text).toList();
        }
    }

    @Test
    void eventsAreDeliveredInOrder() {
        List<TraceEvent> got = Collections.synchronizedList(new ArrayList<>());
        try (EventSink sink = new EventSink("t", 100, got::add)) {
            for (int i = 0; i < 50; i++) sink.onEvent(ev(i));
            assertThat(sink.awaitIdle(Duration.ofSeconds(2))).isTrue();
        }
        assertThat(texts(got))
                .containsExactlyElementsOf(
                        java.util.stream.IntStream.range(0, 50).mapToObj(i -> "e" + i).toList());
    }

    @Test
    void aFullQueueNeverBlocksTheRunAndDropsTheOldestEvents() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        List<TraceEvent> got = Collections.synchronizedList(new ArrayList<>());
        try (EventSink sink =
                new EventSink(
                        "t",
                        3,
                        e -> {
                            try {
                                release.await();
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                            }
                            got.add(e);
                        })) {
            long start = System.nanoTime();
            for (int i = 0; i < 1000; i++)
                sink.onEvent(ev(i)); // the consumer is stuck the whole time
            assertThat(Duration.ofNanos(System.nanoTime() - start))
                    .as("the run was not held up")
                    .isLessThan(Duration.ofSeconds(1));
            assertThat(sink.dropped()).isPositive();
            release.countDown();
            assertThat(sink.awaitIdle(Duration.ofSeconds(2))).isTrue();
        }
        List<String> seen = texts(got);
        assertThat(seen).contains("e999").doesNotContain("e500");
        assertThat(seen.size() + 0L).isLessThanOrEqualTo(1000L);
    }

    @Test
    void aFailingConsumerDoesNotStopLaterEvents() {
        List<TraceEvent> got = Collections.synchronizedList(new ArrayList<>());
        try (EventSink sink =
                new EventSink(
                        "t",
                        10,
                        e -> {
                            if (e.text().equals("e0")) throw new IllegalStateException("boom");
                            got.add(e);
                        })) {
            sink.onEvent(ev(0));
            sink.onEvent(ev(1));
            assertThat(sink.awaitIdle(Duration.ofSeconds(2))).isTrue();
        }
        assertThat(texts(got)).containsExactly("e1");
    }

    @Test
    void closingDeliversWhatIsQueuedAndIgnoresLaterEvents() {
        List<TraceEvent> got = Collections.synchronizedList(new ArrayList<>());
        EventSink sink = new EventSink("t", 100, got::add);
        for (int i = 0; i < 20; i++) sink.onEvent(ev(i));
        sink.close();
        assertThat(got).hasSize(20);
        sink.onEvent(ev(99));
        assertThat(texts(got)).doesNotContain("e99");
    }

    @Test
    void aSinkTheConsumerEndsStopsItsThreadOnceDrained_andLaterEventsAreIgnored() throws Exception {
        List<TraceEvent> got = Collections.synchronizedList(new ArrayList<>());
        EventSink[] self = new EventSink[1];
        self[0] =
                new EventSink(
                        "t",
                        100,
                        e -> {
                            got.add(e);
                            if (e.text().equals("e2")) self[0].finishWhenDrained();
                        });
        for (int i = 0; i < 3; i++) self[0].onEvent(ev(i));
        long end = System.currentTimeMillis() + 3000;
        while (!self[0].ended() && System.currentTimeMillis() < end) Thread.sleep(5);
        assertThat(self[0].ended()).isTrue();
        self[0].onEvent(ev(9)); // nobody reads it, and nobody blocks
        assertThat(texts(got)).containsExactly("e0", "e1", "e2");
    }
}
