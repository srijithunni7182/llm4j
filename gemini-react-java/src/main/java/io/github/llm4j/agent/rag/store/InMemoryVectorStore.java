package io.github.llm4j.agent.rag.store;

import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.RealVector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory vector store using cosine similarity for search.
 * Suitable for small to medium datasets (up to ~10K vectors).
 */
public class InMemoryVectorStore implements VectorStore {

    private final Map<String, VectorEntry> vectors;

    public InMemoryVectorStore() {
        this.vectors = new ConcurrentHashMap<>();
    }

    @Override
    public void add(String id, float[] embedding, Map<String, Object> metadata) {
        Objects.requireNonNull(id, "id cannot be null");
        Objects.requireNonNull(embedding, "embedding cannot be null");

        Map<String, Object> metadataCopy = metadata != null ? new HashMap<>(metadata) : new HashMap<>();

        vectors.put(id, new VectorEntry(id, embedding, metadataCopy));
    }

    @Override
    public void addBatch(List<VectorEntry> entries) {
        Objects.requireNonNull(entries, "entries cannot be null");

        for (VectorEntry entry : entries) {
            add(entry.getId(), entry.getEmbedding(), entry.getMetadata());
        }
    }

    @Override
    public List<SearchResult> search(float[] queryEmbedding, int topK) {
        return search(queryEmbedding, topK, null);
    }

    @Override
    public List<SearchResult> search(float[] queryEmbedding, int topK, Map<String, Object> filters) {
        Objects.requireNonNull(queryEmbedding, "queryEmbedding cannot be null");

        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be positive");
        }

        RealVector queryVector = new ArrayRealVector(toDoubleArray(queryEmbedding));

        // Calculate similarities for all vectors
        List<SearchResult> results = vectors.values().stream()
                .filter(entry -> matchesFilters(entry.getMetadata(), filters))
                .map(entry -> {
                    RealVector entryVector = new ArrayRealVector(toDoubleArray(entry.getEmbedding()));
                    float similarity = (float) cosineSimilarity(queryVector, entryVector);
                    return new SearchResult(entry.getId(), similarity, entry.getMetadata());
                })
                .sorted((a, b) -> Float.compare(b.getSimilarity(), a.getSimilarity()))
                .limit(topK)
                .collect(Collectors.toList());

        return results;
    }

    @Override
    public boolean delete(String id) {
        return vectors.remove(id) != null;
    }

    @Override
    public int size() {
        return vectors.size();
    }

    @Override
    public void clear() {
        vectors.clear();
    }

    /**
     * Calculates cosine similarity between two vectors.
     *
     * @param v1 first vector
     * @param v2 second vector
     * @return cosine similarity (0 to 1)
     */
    private double cosineSimilarity(RealVector v1, RealVector v2) {
        double dotProduct = v1.dotProduct(v2);
        double norm1 = v1.getNorm();
        double norm2 = v2.getNorm();

        if (norm1 == 0 || norm2 == 0) {
            return 0;
        }

        return dotProduct / (norm1 * norm2);
    }

    /**
     * Checks if metadata matches the given filters.
     *
     * @param metadata the metadata to check
     * @param filters  the filters to apply (null means no filtering)
     * @return true if metadata matches all filters
     */
    private boolean matchesFilters(Map<String, Object> metadata, Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }

        for (Map.Entry<String, Object> filter : filters.entrySet()) {
            Object metadataValue = metadata.get(filter.getKey());
            Object filterValue = filter.getValue();

            if (metadataValue == null || !metadataValue.equals(filterValue)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Converts float array to double array for Apache Commons Math.
     *
     * @param floats float array
     * @return double array
     */
    private double[] toDoubleArray(float[] floats) {
        double[] doubles = new double[floats.length];
        for (int i = 0; i < floats.length; i++) {
            doubles[i] = floats[i];
        }
        return doubles;
    }
}
