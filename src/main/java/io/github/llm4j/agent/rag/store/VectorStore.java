package io.github.llm4j.agent.rag.store;

import java.util.List;
import java.util.Map;

/**
 * Interface for vector storage and similarity search.
 */
public interface VectorStore {

    /**
     * Adds a vector with its ID and metadata to the store.
     *
     * @param id        unique identifier for the vector
     * @param embedding the embedding vector
     * @param metadata  associated metadata
     */
    void add(String id, float[] embedding, Map<String, Object> metadata);

    /**
     * Adds multiple vectors in batch.
     *
     * @param entries list of vector entries to add
     */
    void addBatch(List<VectorEntry> entries);

    /**
     * Searches for the top-K most similar vectors to the query embedding.
     *
     * @param queryEmbedding the query vector
     * @param topK           number of results to return
     * @return list of search results ordered by similarity (highest first)
     */
    List<SearchResult> search(float[] queryEmbedding, int topK);

    /**
     * Searches for the top-K most similar vectors with metadata filtering.
     *
     * @param queryEmbedding the query vector
     * @param topK           number of results to return
     * @param filters        metadata filters to apply
     * @return list of search results ordered by similarity (highest first)
     */
    List<SearchResult> search(float[] queryEmbedding, int topK, Map<String, Object> filters);

    /**
     * Deletes a vector by its ID.
     *
     * @param id the ID of the vector to delete
     * @return true if the vector was deleted, false if it didn't exist
     */
    boolean delete(String id);

    /**
     * Returns the number of vectors in the store.
     *
     * @return vector count
     */
    int size();

    /**
     * Clears all vectors from the store.
     */
    void clear();

    /**
     * Represents a vector entry with ID, embedding, and metadata.
     */
    class VectorEntry {
        private final String id;
        private final float[] embedding;
        private final Map<String, Object> metadata;

        public VectorEntry(String id, float[] embedding, Map<String, Object> metadata) {
            this.id = id;
            this.embedding = embedding;
            this.metadata = metadata;
        }

        public String getId() {
            return id;
        }

        public float[] getEmbedding() {
            return embedding;
        }

        public Map<String, Object> getMetadata() {
            return metadata;
        }
    }

    /**
     * Represents a search result with ID, similarity score, and metadata.
     */
    class SearchResult {
        private final String id;
        private final float similarity;
        private final Map<String, Object> metadata;

        public SearchResult(String id, float similarity, Map<String, Object> metadata) {
            this.id = id;
            this.similarity = similarity;
            this.metadata = metadata;
        }

        public String getId() {
            return id;
        }

        public float getSimilarity() {
            return similarity;
        }

        public Map<String, Object> getMetadata() {
            return metadata;
        }

        @Override
        public String toString() {
            return "SearchResult{" +
                    "id='" + id + '\'' +
                    ", similarity=" + similarity +
                    '}';
        }
    }
}
