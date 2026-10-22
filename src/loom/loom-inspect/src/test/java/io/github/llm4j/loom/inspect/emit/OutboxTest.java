package io.github.llm4j.loom.inspect.emit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OutboxTest {

    @TempDir Path dir;

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String s(Outbox.Entry e) {
        return new String(e.payload(), StandardCharsets.UTF_8);
    }

    private Outbox open(long segment, long max, Duration age, Clock clock) {
        return new Outbox(dir, segment, max, age, clock);
    }

    private Outbox open() {
        return open(8 * 1024 * 1024, 256L * 1024 * 1024, Duration.ofDays(7), Clock.systemUTC());
    }

    @Test
    void recordsComeBackInOrderWithTheirOffsets_andReadingAfterAnOffsetSkipsWhatWasAcknowledged() {
        try (Outbox o = open()) {
            assertThat(o.append(b("a"))).isEqualTo(1);
            assertThat(o.append(b("b"))).isEqualTo(2);
            assertThat(o.append(b("c"))).isEqualTo(3);
            assertThat(o.read(0, 10)).extracting(Outbox.Entry::offset).containsExactly(1L, 2L, 3L);
            assertThat(o.read(1, 10)).extracting(OutboxTest::s).containsExactly("b", "c");
        }
    }

    @Test
    void aBatchIsAtMostAsLargeAsAskedFor() {
        try (Outbox o = open()) {
            for (int i = 0; i < 5; i++) o.append(b("r" + i));
            List<Outbox.Entry> two = o.read(1, 2);
            assertThat(two).extracting(OutboxTest::s).containsExactly("r1", "r2");
        }
    }

    @Test
    void reopeningContinuesTheOffsets_andARecordCutOffByACrashIsDroppedWithTheRestIntact()
            throws Exception {
        try (Outbox o = open()) {
            o.append(b("one"));
            o.append(b("two"));
            o.append(b("three"));
            o.flush();
        }
        Path seg =
                Files.list(dir)
                        .filter(p -> p.getFileName().toString().startsWith("seg-"))
                        .findFirst()
                        .orElseThrow();
        try (RandomAccessFile f = new RandomAccessFile(seg.toFile(), "rw")) {
            f.setLength(f.length() - 2); // the last record is cut off mid-write
        }
        try (Outbox o = open()) {
            assertThat(o.read(0, 10)).extracting(OutboxTest::s).containsExactly("one", "two");
            assertThat(o.append(b("four")))
                    .isEqualTo(3); // the cut record's offset is reused: it was never delivered
            assertThat(o.read(0, 10))
                    .extracting(OutboxTest::s)
                    .containsExactly("one", "two", "four");
        }
    }

    @Test
    void aFlippedByteIsFoundByTheChecksum() throws Exception {
        try (Outbox o = open()) {
            o.append(b("one"));
            o.append(b("two"));
            o.flush();
            Path seg =
                    Files.list(dir)
                            .filter(p -> p.getFileName().toString().startsWith("seg-"))
                            .findFirst()
                            .orElseThrow();
            try (RandomAccessFile f = new RandomAccessFile(seg.toFile(), "rw")) {
                f.seek(f.length() - 1);
                f.write('X');
            }
            assertThat(o.read(0, 10)).extracting(OutboxTest::s).containsExactly("one");
        }
    }

    @Test
    void theOldestSegmentsGoFirstWhenTheLogPassesItsSize_andTheFirstOffsetShowsWhatWasLost() {
        try (Outbox o = open(200, 700, Duration.ofDays(7), Clock.systemUTC())) {
            for (int i = 0; i < 60; i++) o.append(b("record-number-" + i));
            o.flush();
            assertThat(o.firstOffset()).isGreaterThan(1);
            assertThat(o.read(0, 100).get(0).offset()).isEqualTo(o.firstOffset());
            assertThat(o.nextOffset()).isEqualTo(61);
        }
    }

    @Test
    void segmentsOlderThanTheAgeCapAreDropped() throws Exception {
        Clock later = Clock.fixed(Instant.now().plus(Duration.ofDays(8)), ZoneOffset.UTC);
        try (Outbox o = open(100, Long.MAX_VALUE, Duration.ofDays(7), Clock.systemUTC())) {
            for (int i = 0; i < 10; i++) o.append(b("old-record-" + i));
            o.flush();
        }
        try (Outbox o = new Outbox(dir, 100, Long.MAX_VALUE, Duration.ofDays(7), later)) {
            o.append(b("new")); // rolls into a fresh segment, which applies the cap
            o.flush();
            assertThat(o.read(0, 100)).extracting(OutboxTest::s).containsExactly("new");
        }
    }

    @Test
    void trimDeletesOnlySegmentsEveryReaderHasPassed() {
        try (Outbox o = open(100, Long.MAX_VALUE, Duration.ofDays(7), Clock.systemUTC())) {
            for (int i = 0; i < 20; i++) o.append(b("record-" + i));
            o.flush();
            o.trim(10);
            assertThat(o.firstOffset()).isLessThanOrEqualTo(11);
            assertThat(o.read(10, 100))
                    .extracting(Outbox.Entry::offset)
                    .startsWith(11L)
                    .endsWith(20L);
        }
    }

    @Test
    void aSecondOpenOfTheSameDirectoryIsRefused_andAFinishedOneReadsAsNotInUse() {
        Outbox o = open();
        assertThat(Outbox.inUse(dir)).isTrue();
        assertThatThrownBy(this::open)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("in use");
        o.close();
        assertThat(Outbox.inUse(dir)).isFalse();
    }

    @Test
    void aCursorOnlyMovesForwardAndIsRememberedAcrossRestarts() {
        Cursor c = new Cursor(dir, "fabric");
        assertThat(c.get()).isZero();
        c.advance(5);
        c.advance(3);
        assertThat(c.get()).isEqualTo(5);
        assertThat(new Cursor(dir, "fabric").get()).isEqualTo(5);
        assertThat(new Cursor(dir, "other").get()).isZero();
    }
}
