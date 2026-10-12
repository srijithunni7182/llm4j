package io.github.llm4j.loom.fabric;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoryTest {

    @TempDir
    Path dir;

    private static Clock at(String iso) {
        return Clock.fixed(Instant.parse(iso), ZoneOffset.UTC);
    }

    @Test
    void aTornFinalLineIsDroppedAndTheRestIsKept() throws Exception {
        History h = new History(dir, at("2026-10-11T10:00:00Z"));
        h.append("alpha", "hello", "{\"a\":1}");
        h.append("alpha", "logs", "{\"b\":2}");
        Path day = dir.resolve("history/alpha/2026-10-11.jsonl");
        Files.writeString(day, Files.readString(day) + "{\"k\":\"logs\",\"t\":\"2026-10-1", java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        List<String> seen = new ArrayList<>();
        h.replay(e -> seen.add(e.kind() + e.body()));
        assertThat(seen).containsExactly("hello{\"a\":1}", "logs{\"b\":2}");
    }

    @Test
    void daysOlderThanTheRetentionGoFirst_thenTheOldestUntilTheSizeFits() throws Exception {
        for (String day : List.of("2026-08-01", "2026-10-09", "2026-10-10", "2026-10-11")) {
            Path f = dir.resolve("history/alpha/" + day + ".jsonl");
            Files.createDirectories(f.getParent());
            Files.writeString(f, "x".repeat(100) + "\n");
        }
        History h = new History(dir, at("2026-10-11T12:00:00Z"));
        assertThat(h.enforce(30, 10_000)).isEqualTo(1); // August is past 30 days
        assertThat(Files.exists(dir.resolve("history/alpha/2026-08-01.jsonl"))).isFalse();
        assertThat(h.enforce(30, 250)).isEqualTo(1); // 303 bytes over a 250 cap: the oldest of the rest goes
        assertThat(Files.exists(dir.resolve("history/alpha/2026-10-09.jsonl"))).isFalse();
        assertThat(Files.exists(dir.resolve("history/alpha/2026-10-11.jsonl"))).isTrue();
    }
}
