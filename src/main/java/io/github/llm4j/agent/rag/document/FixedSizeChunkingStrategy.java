package io.github.llm4j.agent.rag.document;

import java.util.ArrayList;
import java.util.List;

/**
 * Fixed-size chunking strategy that splits documents into chunks of a specified
 * size
 * with optional overlap between chunks.
 */
public class FixedSizeChunkingStrategy implements ChunkingStrategy {

    private final int chunkSize;
    private final int overlap;

    /**
     * Creates a fixed-size chunking strategy.
     *
     * @param chunkSize the size of each chunk in characters
     * @param overlap   the number of overlapping characters between chunks
     */
    public FixedSizeChunkingStrategy(int chunkSize, int overlap) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive");
        }
        if (overlap < 0) {
            throw new IllegalArgumentException("overlap cannot be negative");
        }
        if (overlap >= chunkSize) {
            throw new IllegalArgumentException("overlap must be less than chunkSize");
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    /**
     * Creates a fixed-size chunking strategy with no overlap.
     *
     * @param chunkSize the size of each chunk in characters
     */
    public FixedSizeChunkingStrategy(int chunkSize) {
        this(chunkSize, 0);
    }

    @Override
    public List<DocumentChunk> chunk(Document document) {
        List<DocumentChunk> chunks = new ArrayList<>();
        String content = document.getContent();
        int step = chunkSize - overlap;

        for (int i = 0; i < content.length(); i += step) {
            int endIndex = Math.min(i + chunkSize, content.length());
            String chunkContent = content.substring(i, endIndex);

            DocumentChunk chunk = DocumentChunk.builder()
                    .id(document.getId() + "_chunk_" + chunks.size())
                    .documentId(document.getId())
                    .content(chunkContent)
                    .startIndex(i)
                    .endIndex(endIndex)
                    .metadata(document.getMetadata())
                    .build();

            chunks.add(chunk);

            // Break if we've reached the end
            if (endIndex >= content.length()) {
                break;
            }
        }

        return chunks;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public int getOverlap() {
        return overlap;
    }
}
