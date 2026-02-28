package io.github.llm4j.agent.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A zero-configuration, in-memory vector store backed by a {@link ConcurrentHashMap}.
 * Uses brute-force cosine similarity search — not suitable for large datasets but ideal
 * for local development, testing, and small-scale agent memory.
 *
 * <p>No dependencies required. Instantiate with {@code new InMemoryVectorStore()}.
 */
public class InMemoryVectorStore implements VectorStore {

    private static final Logger logger = LoggerFactory.getLogger(InMemoryVectorStore.class);

    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    @Override
    public void add(String id, float[] embedding, Map<String, Object> metadata) {
        Objects.requireNonNull(id, "id cannot be null");
        Objects.requireNonNull(embedding, "embedding cannot be null");
        store.put(id, new Entry(id, embedding, metadata != null ? new HashMap<>(metadata) : new HashMap<>()));
        logger.debug("Stored vector entry with id: {}", id);
    }

    @Override
    public List<SearchResult> search(float[] queryEmbedding, int topK, Map<String, Object> filters) {
        Objects.requireNonNull(queryEmbedding, "queryEmbedding cannot be null");

        List<SearchResult> results = new ArrayList<>();
        for (Entry entry : store.values()) {
            if (matchesFilters(entry.metadata, filters)) {
                float similarity = cosineSimilarity(queryEmbedding, entry.embedding);
                results.add(new SearchResult(entry.id, similarity, entry.metadata));
            }
        }

        // Sort by similarity descending, return top K
        results.sort((a, b) -> Float.compare(b.getSimilarity(), a.getSimilarity()));
        return results.subList(0, Math.min(topK, results.size()));
    }

    @Override
    public List<SearchResult> search(float[] queryEmbedding, int topK) {
        return search(queryEmbedding, topK, null);
    }

    @Override
    public boolean delete(String id) {
        return store.remove(id) != null;
    }

    @Override
    public int size() {
        return store.size();
    }

    @Override
    public void clear() {
        store.clear();
    }

    // --- Helpers ---

    private boolean matchesFilters(Map<String, Object> metadata, Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) return true;
        for (Map.Entry<String, Object> filter : filters.entrySet()) {
            Object metaVal = metadata.get(filter.getKey());
            if (metaVal == null || !metaVal.equals(filter.getValue())) return false;
        }
        return true;
    }

    /**
     * Computes the cosine similarity between two vectors.
     * Both vectors should already be normalized (L2) for accurate results.
     */
    private float cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vectors must be the same dimension: " + a.length + " vs " + b.length);
        }
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0f;
        return (float) (dot / (Math.sqrt(normA) * Math.sqrt(normB)));
    }

    private static class Entry {
        final String id;
        final float[] embedding;
        final Map<String, Object> metadata;

        Entry(String id, float[] embedding, Map<String, Object> metadata) {
            this.id = id;
            this.embedding = embedding;
            this.metadata = metadata;
        }
    }
}
