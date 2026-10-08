package io.github.llm4j.eval.judge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemJudgeCacheTest {

    @TempDir private Path tempDir;

    @Test
    void get_isEmptyWhenNoEntryWritten() {
        FileSystemJudgeCache cache = FileSystemJudgeCache.at(tempDir.resolve("cache"));
        assertThat(cache.get("missing")).isEmpty();
    }

    @Test
    void put_thenGet_returnsTheStoredVerdict() {
        FileSystemJudgeCache cache = FileSystemJudgeCache.at(tempDir.resolve("cache"));
        JudgeVerdict verdict = new JudgeVerdict(0.75, "[4/5] good");

        cache.put("key1", verdict);

        assertThat(cache.get("key1")).contains(verdict);
    }

    @Test
    void at_createsTheCacheDirectoryIfMissing() {
        Path cacheDir = tempDir.resolve("nested").resolve("cache-dir");
        assertThat(Files.exists(cacheDir)).isFalse();

        FileSystemJudgeCache.at(cacheDir);

        assertThat(Files.isDirectory(cacheDir)).isTrue();
    }

    @Test
    void separateInstancesAtTheSameDirectory_shareEntries() {
        Path cacheDir = tempDir.resolve("shared");
        FileSystemJudgeCache writer = FileSystemJudgeCache.at(cacheDir);
        writer.put("key1", new JudgeVerdict(1.0, "cached across instances"));

        FileSystemJudgeCache reader = FileSystemJudgeCache.at(cacheDir);

        assertThat(reader.get("key1")).contains(new JudgeVerdict(1.0, "cached across instances"));
    }

    @Test
    void get_treatsACorruptEntryAsAMiss() throws IOException {
        Path cacheDir = tempDir.resolve("cache");
        FileSystemJudgeCache cache = FileSystemJudgeCache.at(cacheDir);
        Files.writeString(cacheDir.resolve("corrupt-key.json"), "{ not valid json");

        assertThat(cache.get("corrupt-key")).isEmpty();
    }
}
