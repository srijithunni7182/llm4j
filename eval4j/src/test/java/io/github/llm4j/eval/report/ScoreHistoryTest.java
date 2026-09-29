package io.github.llm4j.eval.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScoreHistoryTest {

    private static HistoryEntry entry(int i) {
        return new HistoryEntry("run" + i, "2026-01-01T00:00:0" + (i % 10) + "Z", null, Map.of("M", i / 10.0));
    }

    @Test
    void appendAndLoadPreserveOrder(@TempDir Path dir) {
        var history = new FileSystemScoreHistory(dir.resolve("h.jsonl"));
        history.append(entry(1));
        history.append(entry(2));
        assertThat(history.load()).extracting(HistoryEntry::runId).containsExactly("run1", "run2");
    }

    @Test
    void retentionKeepsNewestEntries(@TempDir Path dir) {
        var history = new FileSystemScoreHistory(dir.resolve("h.jsonl"), 3);
        for (int i = 1; i <= 5; i++) {
            history.append(entry(i));
        }
        assertThat(history.load()).extracting(HistoryEntry::runId).containsExactly("run3", "run4", "run5");
    }

    @Test
    void missingFileIsEmptyHistory(@TempDir Path dir) {
        assertThat(new FileSystemScoreHistory(dir.resolve("nope.jsonl")).load()).isEmpty();
    }

    @Test
    void corruptLineIsSkippedOthersPreserved(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("h.jsonl");
        var history = new FileSystemScoreHistory(file);
        history.append(entry(1));
        Files.writeString(file, Files.readString(file) + "{this is not json\n");
        history.append(entry(2));
        assertThat(history.load()).extracting(HistoryEntry::runId).containsExactly("run1", "run2");
    }

    @Test
    void concurrentAppendsNeverInterleaveLines(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("h.jsonl");
        var history = new FileSystemScoreHistory(file, 1000);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            IntStream.range(0, 40).forEach(i -> futures.add(pool.submit(() -> history.append(entry(i)))));
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(history.load()).hasSize(40);
        assertThat(Files.readAllLines(file)).allSatisfy(line -> assertThat(line).startsWith("{").endsWith("}"));
    }

    @Test
    void retentionMustBePositive(@TempDir Path dir) {
        assertThatThrownBy(() -> new FileSystemScoreHistory(dir.resolve("h"), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
