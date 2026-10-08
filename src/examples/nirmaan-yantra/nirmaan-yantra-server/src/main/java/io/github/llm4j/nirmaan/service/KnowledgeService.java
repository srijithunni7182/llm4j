package io.github.llm4j.nirmaan.service;

import io.github.llm4j.agent.rag.embedding.EmbeddingProvider;
import io.github.llm4j.agent.rag.store.VectorStore.SearchResult;
import io.github.llm4j.agent.rag.store.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

@Service
public class KnowledgeService {

    private static final Logger log = Logger.getLogger(KnowledgeService.class.getName());

    private final VectorStore vectorStore;
    private final EmbeddingProvider embeddingProvider;

    @Autowired
    public KnowledgeService(
            @Autowired(required = false) VectorStore vectorStore,
            @Autowired(required = false) EmbeddingProvider embeddingProvider) {
        this.vectorStore = vectorStore;
        this.embeddingProvider = embeddingProvider;
        if (vectorStore == null || embeddingProvider == null) {
            log.warning(
                    "RAG Components missing (VectorStore or EmbeddingProvider). Knowledge Service running in simplified mode.");
        } else {
            log.info("Knowledge Service initialized with RAG support.");
        }
    }

    public void remember(String content, Map<String, Object> metadata) {
        if (!isRagEnabled()) {
            return;
        }
        try {
            float[] vector = embeddingProvider.embed(content);
            String id = UUID.randomUUID().toString();
            vectorStore.add(id, vector, metadata);
            log.info("Remembered info: " + id);
        } catch (Exception e) {
            log.severe("Failed to remember content: " + e.getMessage());
        }
    }

    public List<String> hybridSearch(String query) {
        List<String> combined = new java.util.ArrayList<>();

        // 1. Check Memory (Local Vector DB)
        if (isRagEnabled()) {
            try {
                List<SearchResult> memories = search(query, 3);
                for (SearchResult mem : memories) {
                    String content = (String) mem.getMetadata().get("content");
                    if (content != null) {
                        combined.add("[MEMORY] " + content);
                    }
                }
            } catch (Exception e) {
                log.warning("Memory search failed: " + e.getMessage());
            }
        }

        // 2. Check Web
        combined.addAll(io.github.llm4j.nirmaan.util.SearchUtil.search(query));

        return combined;
    }

    public List<SearchResult> search(String query, int limit) {
        if (!isRagEnabled()) {
            return Collections.emptyList();
        }
        try {
            float[] queryVector = embeddingProvider.embed(query);
            return vectorStore.search(queryVector, limit);
        } catch (Exception e) {
            log.severe("Search failed: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    public boolean isRagEnabled() {
        return vectorStore != null && embeddingProvider != null;
    }
}
