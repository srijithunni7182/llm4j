package io.github.llm4j.agent.memory;

import java.util.List;

/**
 * Interface for providing semantic embeddings of text.
 */
public interface EmbeddingProvider {
    float[] embed(String text);
    List<float[]> embedBatch(List<String> texts);
    int getDimensions();
}
