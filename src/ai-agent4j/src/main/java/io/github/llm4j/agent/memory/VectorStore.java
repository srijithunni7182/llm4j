package io.github.llm4j.agent.memory;

import java.util.List;
import java.util.Map;

public interface VectorStore {
    void add(String id, float[] embedding, Map<String, Object> metadata);

    List<SearchResult> search(float[] queryEmbedding, int topK, Map<String, Object> filters);

    List<SearchResult> search(float[] queryEmbedding, int topK);

    boolean delete(String id);

    int size();

    void clear();
}
