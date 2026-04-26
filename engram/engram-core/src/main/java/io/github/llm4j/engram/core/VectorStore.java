package io.github.llm4j.engram.core;

import io.github.llm4j.engram.core.models.MemoryObject;
import io.github.llm4j.engram.core.models.ScoredMemory;

import java.util.List;

/**
 * Pluggable vector store interface for Engram memory persistence.
 * Implementations can be backed by an in-process store, PostgreSQL (pgvector), Pinecone, etc.
 */
public interface VectorStore {

    /**
     * Embeds a text string into a float vector.
     *
     * @param text the raw text to embed.
     * @return the embedding vector.
     */
    float[] embed(String text);

    /**
     * Adds a memory object to the store.
     *
     * @param memory the memory object to store.
     */
    void add(MemoryObject memory);

    /**
     * Removes all memory objects whose content exactly matches the given string.
     *
     * @param content the exact content string to match and delete.
     */
    void removeByContent(String content);

    /**
     * Scores and retrieves the top-N candidate memories for a given task intent.
     *
     * @param taskIntent the intent of the current task.
     * @param topN       the maximum number of candidates to return.
     * @param minScore   the minimum score threshold for candidates.
     * @return a ranked list of scored memories.
     */
    List<ScoredMemory> scoreCandidates(String taskIntent, int topN, double minScore);

    /**
     * Flushes any buffered state to the underlying persistence layer.
     * No-op for implementations that commit immediately.
     */
    void save();
}
