package io.github.llm4j.agent.memory;

import static org.junit.jupiter.api.Assertions.*;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileMemoryVectorStoreTest {

    @TempDir Path dir;

    @Test
    void vectorsSurviveANewStore() {
        Path file = dir.resolve("facts/user.json");
        FileMemoryVectorStore store = new FileMemoryVectorStore(file);
        store.add("a", new float[] {1, 0}, Map.of("fact", "likes tea", "userId", "u1"));
        store.add("b", new float[] {0, 1}, Map.of("fact", "lives in Pune", "userId", "u2"));

        FileMemoryVectorStore reopened = new FileMemoryVectorStore(file);
        assertEquals(2, reopened.size());
        var hits = reopened.search(new float[] {1, 0}, 1, Map.of("userId", "u1"));
        assertEquals("likes tea", hits.get(0).getMetadata().get("fact"));
        assertEquals(file, reopened.getFile());
    }

    @Test
    void deleteAndClearArePersisted() {
        Path file = dir.resolve("m.json");
        FileMemoryVectorStore store = new FileMemoryVectorStore(file);
        store.add("a", new float[] {1, 0}, null);
        store.add("b", new float[] {0, 1}, Map.of());
        assertTrue(store.delete("a"));
        assertFalse(store.delete("missing"));
        assertEquals(1, new FileMemoryVectorStore(file).size());
        store.clear();
        assertEquals(0, new FileMemoryVectorStore(file).size());
    }

    @Test
    void aCorruptFileIsReported() throws Exception {
        Path file = dir.resolve("bad.json");
        Files.writeString(file, "{not json");
        UncheckedIOException e = assertThrows(UncheckedIOException.class, () -> new FileMemoryVectorStore(file));
        assertTrue(e.getMessage().contains("bad.json"));
    }
}
