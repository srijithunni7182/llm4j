package io.github.llm4j.agent.memory;

import io.github.llm4j.agent.rag.embedding.EmbeddingProvider;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A service that acts as a bridge between the agent's context and a persistent VectorStore.
 * It uses an EmbeddingProvider to turn user facts into vectors, and stores them.
 * This provides the agent with "Long Term Semantic Memory".
 */
public class SemanticMemoryService {

    private static final Logger logger = LoggerFactory.getLogger(SemanticMemoryService.class);

    private final EmbeddingProvider embeddingProvider;
    private final VectorStore vectorStore;
    private final String userId;

    /**
     * Constructs a SemanticMemoryService.
     * @param embeddingProvider the provider used to embed text
     * @param vectorStore the persistent store 
     * @param userId an identifier to namespace facts for a specific user.
     */
    public SemanticMemoryService(EmbeddingProvider embeddingProvider, VectorStore vectorStore, String userId) {
        this.embeddingProvider = Objects.requireNonNull(embeddingProvider, "embeddingProvider cannot be null");
        this.vectorStore = Objects.requireNonNull(vectorStore, "vectorStore cannot be null");
        this.userId = Objects.requireNonNull(userId, "userId cannot be null");
    }

    /**
     * Embeds a fact and saves it to the persistent memory store.
     * @param fact a string representing a factual statement
     */
    public void saveFact(String fact) {
        if (fact == null || fact.trim().isEmpty()) {
            return;
        }

        try {
            float[] embedding = embeddingProvider.embed(fact);
            String memoryId = UUID.randomUUID().toString();
            
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("userId", userId);
            metadata.put("fact", fact);
            metadata.put("timestamp", System.currentTimeMillis());

            vectorStore.add(memoryId, embedding, metadata);
            logger.info("Saved new semantic memory fact for user {}: '{}'", userId, fact);
        } catch (Exception e) {
            logger.error("Failed to save semantic memory fact.", e);
            throw new RuntimeException("Failed to save memory fact", e);
        }
    }

    /**
     * Recalls relevant facts based on semantic similarity to a prompt.
     * @param query the prompt or question being asked
     * @param topK the maximum number of facts to return
     * @param similarityThreshold the minimum similarity score (0.0 to 1.0) required to include a fact
     * @return a list of relevant fact strings
     */
    public List<String> recallRelevantFacts(String query, int topK, float similarityThreshold) {
        if (query == null || query.trim().isEmpty()) {
            return List.of();
        }

        try {
            float[] queryEmbedding = embeddingProvider.embed(query);
            
            // Filter by user ID
            Map<String, Object> filters = new HashMap<>();
            filters.put("userId", userId);

            List<SearchResult> results = vectorStore.search(queryEmbedding, topK, filters);
            
            return results.stream()
                    .filter(result -> result.getSimilarity() >= similarityThreshold)
                    .map(result -> (String) result.getMetadata().get("fact"))
                    .toList();
        } catch (Exception e) {
            logger.error("Failed to recall semantic memory facts.", e);
            return List.of(); // Fail gracefully so the agent doesn't crash on memory failure
        }
    }
}
