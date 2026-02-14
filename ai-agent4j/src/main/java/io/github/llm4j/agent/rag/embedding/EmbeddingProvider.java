package io.github.llm4j.agent.rag.embedding;

import java.util.List;

/**
 * Interface for generating embeddings from text.
 */
public interface EmbeddingProvider {

    /**
     * Generates an embedding vector for the given text.
     *
     * @param text the text to embed
     * @return the embedding vector
     */
    float[] embed(String text);

    /**
     * Generates embedding vectors for a batch of texts.
     * Implementations may optimize batch processing.
     *
     * @param texts the texts to embed
     * @return list of embedding vectors
     */
    List<float[]> embedBatch(List<String> texts);

    /**
     * Returns the dimensionality of the embedding vectors.
     *
     * @return embedding dimension
     */
    int getDimensions();
}
