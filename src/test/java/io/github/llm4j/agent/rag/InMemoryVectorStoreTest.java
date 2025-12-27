package io.github.llm4j.agent.rag;

import io.github.llm4j.agent.rag.store.InMemoryVectorStore;
import io.github.llm4j.agent.rag.store.VectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for InMemoryVectorStore
 */
class InMemoryVectorStoreTest {

    private InMemoryVectorStore vectorStore;

    @BeforeEach
    void setUp() {
        vectorStore = new InMemoryVectorStore();
    }

    @Test
    void testAddAndSearch() {
        float[] embedding1 = { 1.0f, 0.0f, 0.0f };
        float[] embedding2 = { 0.0f, 1.0f, 0.0f };
        float[] embedding3 = { 0.0f, 0.0f, 1.0f };

        vectorStore.add("vec1", embedding1, Map.of("type", "A"));
        vectorStore.add("vec2", embedding2, Map.of("type", "B"));
        vectorStore.add("vec3", embedding3, Map.of("type", "C"));

        assertThat(vectorStore.size()).isEqualTo(3);

        // Search for vector similar to embedding1
        List<VectorStore.SearchResult> results = vectorStore.search(embedding1, 2);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getId()).isEqualTo("vec1"); // Should be most similar to itself
        assertThat(results.get(0).getSimilarity()).isCloseTo(1.0f, org.assertj.core.data.Offset.offset(0.01f));
    }

    @Test
    void testCosineSimilarity() {
        // Identical vectors should have similarity of 1
        float[] vec1 = { 1.0f, 2.0f, 3.0f };
        float[] vec2 = { 1.0f, 2.0f, 3.0f };

        vectorStore.add("vec1", vec1, new HashMap<>());
        vectorStore.add("vec2", vec2, new HashMap<>());

        List<VectorStore.SearchResult> results = vectorStore.search(vec1, 2);

        assertThat(results).hasSize(2);
        // Both should have similarity close to 1
        assertThat(results.get(0).getSimilarity()).isCloseTo(1.0f, org.assertj.core.data.Offset.offset(0.01f));
        assertThat(results.get(1).getSimilarity()).isCloseTo(1.0f, org.assertj.core.data.Offset.offset(0.01f));
    }

    @Test
    void testMetadataFiltering() {
        float[] embedding = { 1.0f, 0.0f, 0.0f };

        vectorStore.add("vec1", embedding, Map.of("category", "science", "year", 2020));
        vectorStore.add("vec2", embedding, Map.of("category", "history", "year", 2020));
        vectorStore.add("vec3", embedding, Map.of("category", "science", "year", 2021));

        // Filter by category
        Map<String, Object> filters = Map.of("category", "science");
        List<VectorStore.SearchResult> results = vectorStore.search(embedding, 10, filters);

        assertThat(results).hasSize(2);
        assertThat(results).allMatch(r -> r.getMetadata().get("category").equals("science"));
    }

    @Test
    void testDelete() {
        float[] embedding = { 1.0f, 0.0f, 0.0f };
        vectorStore.add("vec1", embedding, new HashMap<>());

        assertThat(vectorStore.size()).isEqualTo(1);

        boolean deleted = vectorStore.delete("vec1");
        assertThat(deleted).isTrue();
        assertThat(vectorStore.size()).isEqualTo(0);

        // Deleting non-existent vector
        boolean deletedAgain = vectorStore.delete("vec1");
        assertThat(deletedAgain).isFalse();
    }

    @Test
    void testClear() {
        vectorStore.add("vec1", new float[] { 1.0f, 0.0f }, new HashMap<>());
        vectorStore.add("vec2", new float[] { 0.0f, 1.0f }, new HashMap<>());

        assertThat(vectorStore.size()).isEqualTo(2);

        vectorStore.clear();
        assertThat(vectorStore.size()).isEqualTo(0);
    }

    @Test
    void testBatchAdd() {
        List<VectorStore.VectorEntry> entries = Arrays.asList(
                new VectorStore.VectorEntry("vec1", new float[] { 1.0f, 0.0f }, Map.of("id", 1)),
                new VectorStore.VectorEntry("vec2", new float[] { 0.0f, 1.0f }, Map.of("id", 2)),
                new VectorStore.VectorEntry("vec3", new float[] { 1.0f, 1.0f }, Map.of("id", 3)));

        vectorStore.addBatch(entries);
        assertThat(vectorStore.size()).isEqualTo(3);
    }

    @Test
    void testTopKLimiting() {
        for (int i = 0; i < 10; i++) {
            float[] embedding = new float[] { (float) i, 0.0f };
            vectorStore.add("vec" + i, embedding, new HashMap<>());
        }

        float[] query = { 5.0f, 0.0f };
        List<VectorStore.SearchResult> results = vectorStore.search(query, 3);

        assertThat(results).hasSize(3);
    }

    @Test
    void testEmptyStoreSearch() {
        float[] query = { 1.0f, 0.0f };
        List<VectorStore.SearchResult> results = vectorStore.search(query, 5);

        assertThat(results).isEmpty();
    }
}
