package io.github.llm4j.agent.rag.document;

import java.util.List;

/**
 * Strategy interface for chunking documents into smaller pieces.
 */
public interface ChunkingStrategy {

    /**
     * Chunks a document into smaller pieces based on the strategy.
     *
     * @param document the document to chunk
     * @return list of document chunks
     */
    List<DocumentChunk> chunk(Document document);
}
