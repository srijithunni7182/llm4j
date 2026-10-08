package io.github.llm4j.engram.core.models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

public class MemoryObject {
    private final String id;
    private final String content;
    private final float[] embedding;
    private final MemoryTier tier;
    private final double importance;
    private final String topicKey;
    private int reinforcementCount;
    private boolean shadow;
    private Instant lastAccessedAt;

    public MemoryObject(String content, float[] embedding, MemoryTier tier, double importance, String topicKey) {
        this.id = UUID.randomUUID().toString();
        this.content = content;
        this.embedding = embedding;
        this.tier = tier;
        this.importance = importance;
        this.topicKey = topicKey;
        this.reinforcementCount = 0;
        this.shadow = false;
        this.lastAccessedAt = Instant.now();
    }

    @JsonCreator
    public MemoryObject(
            @JsonProperty("id") String id,
            @JsonProperty("content") String content,
            @JsonProperty("embedding") float[] embedding,
            @JsonProperty("tier") MemoryTier tier,
            @JsonProperty("importance") double importance,
            @JsonProperty("topicKey") String topicKey,
            @JsonProperty("reinforcementCount") int reinforcementCount,
            @JsonProperty("shadow") boolean shadow,
            @JsonProperty("lastAccessedAt") Instant lastAccessedAt) {
        this.id = id;
        this.content = content;
        this.embedding = embedding;
        this.tier = tier;
        this.importance = importance;
        this.topicKey = topicKey;
        this.reinforcementCount = reinforcementCount;
        this.shadow = shadow;
        this.lastAccessedAt = lastAccessedAt;
    }

    public String getId() { return id; }
    public String getContent() { return content; }
    public float[] getEmbedding() { return embedding; }
    public MemoryTier getTier() { return tier; }
    public double getImportance() { return importance; }
    public String getTopicKey() { return topicKey; }
    
    public int getReinforcementCount() { return reinforcementCount; }
    public void reinforce() { this.reinforcementCount++; }
    
    public boolean isShadow() { return shadow; }
    public void setShadow(boolean shadow) { this.shadow = shadow; }
    
    public Instant getLastAccessedAt() { return lastAccessedAt; }
    public void markAccessed() { this.lastAccessedAt = Instant.now(); }
}
