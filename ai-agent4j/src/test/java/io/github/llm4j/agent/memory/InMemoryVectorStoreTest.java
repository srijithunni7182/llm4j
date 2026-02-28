package io.github.llm4j.agent.memory;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryVectorStoreTest {

    private InMemoryVectorStore store;

    @BeforeEach
    void setup() {
        store = new InMemoryVectorStore();
    }

    @Test
    void testAddAndSize() {
        store.add("id-1", new float[]{0.1f, 0.0f}, Map.of("key", "value"));
        store.add("id-2", new float[]{0.0f, 0.1f}, Map.of("key", "value"));
        assertEquals(2, store.size());
    }

    @Test
    void testSearchReturnsTopKByCosineSimilarity() {
        // id-1 is aligned with the query (pointing in the same direction)
        store.add("id-1", new float[]{1.0f, 0.0f}, Map.of("fact", "Java is awesome"));
        // id-2 is orthogonal to query — zero cosine similarity
        store.add("id-2", new float[]{0.0f, 1.0f}, Map.of("fact", "Python is awesome"));

        float[] query = new float[]{1.0f, 0.0f};
        List<SearchResult> results = store.search(query, 2);

        assertEquals(2, results.size());
        // id-1 should be ranked first with similarity ~1.0
        assertEquals("id-1", results.get(0).getId());
        assertTrue(results.get(0).getSimilarity() > 0.99f);
        // id-2 should be ranked second with similarity ~0.0
        assertEquals("id-2", results.get(1).getId());
        assertEquals(0.0f, results.get(1).getSimilarity(), 0.001f);
    }

    @Test
    void testSearchWithTopKBound() {
        for (int i = 0; i < 5; i++) {
            store.add("id-" + i, new float[]{i * 0.1f, 0.0f}, Map.of());
        }
        List<SearchResult> results = store.search(new float[]{0.5f, 0.0f}, 2);
        assertEquals(2, results.size());
    }

    @Test
    void testSearchWithFilters() {
        store.add("id-user1", new float[]{1.0f, 0.0f}, Map.of("userId", "user-1", "fact", "loves Java"));
        store.add("id-user2", new float[]{1.0f, 0.0f}, Map.of("userId", "user-2", "fact", "loves Python"));

        Map<String, Object> filters = new HashMap<>();
        filters.put("userId", "user-1");
        List<SearchResult> results = store.search(new float[]{1.0f, 0.0f}, 5, filters);

        assertEquals(1, results.size());
        assertEquals("loves Java", results.get(0).getMetadata().get("fact"));
    }

    @Test
    void testDelete() {
        store.add("id-1", new float[]{1.0f}, Map.of());
        assertTrue(store.delete("id-1"));
        assertEquals(0, store.size());
        assertFalse(store.delete("no-such-id"));
    }

    @Test
    void testClear() {
        store.add("id-1", new float[]{1.0f}, Map.of());
        store.add("id-2", new float[]{2.0f}, Map.of());
        store.clear();
        assertEquals(0, store.size());
    }
}
