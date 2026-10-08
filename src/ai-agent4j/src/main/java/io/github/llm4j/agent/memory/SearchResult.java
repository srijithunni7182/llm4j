package io.github.llm4j.agent.memory;

import java.util.Map;

public class SearchResult {
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
}
