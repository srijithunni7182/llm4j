package io.github.llm4j.agent.rag;

import io.github.llm4j.agent.rag.document.Document;
import io.github.llm4j.agent.rag.document.DocumentChunk;
import io.github.llm4j.agent.rag.document.FixedSizeChunkingStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for document chunking
 */
class DocumentChunkingTest {

    @Test
    void testDocumentCreation() {
        Document doc = Document.builder()
                .id("doc1")
                .content("This is a test document")
                .addMetadata("source", "test")
                .build();

        assertThat(doc.getId()).isEqualTo("doc1");
        assertThat(doc.getContent()).isEqualTo("This is a test document");
        assertThat(doc.getMetadata()).containsEntry("source", "test");
    }

    @Test
    void testFixedSizeChunking() {
        Document doc = Document.builder()
                .id("doc1")
                .content("This is a longer document that needs to be chunked into smaller pieces for processing.")
                .build();

        FixedSizeChunkingStrategy strategy = new FixedSizeChunkingStrategy(20, 5);
        List<DocumentChunk> chunks = strategy.chunk(doc);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).getContent()).hasSize(20);
        assertThat(chunks.get(0).getDocumentId()).isEqualTo("doc1");
    }

    @Test
    void testFixedSizeChunkingWithOverlap() {
        String content = "0123456789012345678901234567890123456789"; // 40 chars
        Document doc = Document.builder()
                .id("doc1")
                .content(content)
                .build();

        FixedSizeChunkingStrategy strategy = new FixedSizeChunkingStrategy(15, 5);
        List<DocumentChunk> chunks = strategy.chunk(doc);

        // With chunk size 15 and overlap 5, step is 10
        // Should create chunks at: 0-15, 10-25, 20-35, 30-40
        assertThat(chunks).hasSizeGreaterThan(1);

        // Verify overlap
        if (chunks.size() > 1) {
            String firstChunk = chunks.get(0).getContent();
            String secondChunk = chunks.get(1).getContent();

            // Last 5 chars of first should match first 5 of second
            String firstEnd = firstChunk.substring(firstChunk.length() - 5);
            String secondStart = secondChunk.substring(0, Math.min(5, secondChunk.length()));

            assertThat(firstEnd).isEqualTo(secondStart);
        }
    }

    @Test
    void testChunkingPreservesMetadata() {
        Document doc = Document.builder()
                .id("doc1")
                .content("Test content for chunking")
                .addMetadata("author", "Test Author")
                .addMetadata("date", "2024-01-01")
                .build();

        FixedSizeChunkingStrategy strategy = new FixedSizeChunkingStrategy(10);
        List<DocumentChunk> chunks = strategy.chunk(doc);

        for (DocumentChunk chunk : chunks) {
            assertThat(chunk.getMetadata()).containsEntry("author", "Test Author");
            assertThat(chunk.getMetadata()).containsEntry("date", "2024-01-01");
        }
    }

    @Test
    void testInvalidChunkSize() {
        assertThrows(IllegalArgumentException.class, () -> {
            new FixedSizeChunkingStrategy(0);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            new FixedSizeChunkingStrategy(-1);
        });
    }

    @Test
    void testInvalidOverlap() {
        assertThrows(IllegalArgumentException.class, () -> {
            new FixedSizeChunkingStrategy(10, -1);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            new FixedSizeChunkingStrategy(10, 10); // overlap >= chunkSize
        });
    }

    @Test
    void testChunkPositionTracking() {
        Document doc = Document.builder()
                .id("doc1")
                .content("0123456789")
                .build();

        FixedSizeChunkingStrategy strategy = new FixedSizeChunkingStrategy(5);
        List<DocumentChunk> chunks = strategy.chunk(doc);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).getStartIndex()).isEqualTo(0);
        assertThat(chunks.get(0).getEndIndex()).isEqualTo(5);
        assertThat(chunks.get(1).getStartIndex()).isEqualTo(5);
        assertThat(chunks.get(1).getEndIndex()).isEqualTo(10);
    }
}
