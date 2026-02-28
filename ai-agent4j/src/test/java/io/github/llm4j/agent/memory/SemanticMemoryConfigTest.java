package io.github.llm4j.agent.memory;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SemanticMemoryConfigTest {

    @Test
    void testInMemoryFactory() {
        SemanticMemoryConfig config = SemanticMemoryConfig.inMemory("user-42");
        assertEquals("user-42", config.getUserId());
        assertEquals(SemanticMemoryConfig.EmbeddingMode.IN_MEMORY, config.getEmbeddingMode());
        assertEquals(SemanticMemoryConfig.StoreMode.IN_MEMORY, config.getStoreMode());
        assertEquals(5, config.getTopK());
        assertEquals(0.7f, config.getSimilarityThreshold(), 0.001f);
    }

    @Test
    void testGeminiModeFromBuilder() {
        SemanticMemoryConfig config = SemanticMemoryConfig.builder()
                .userId("user-1")
                .geminiApiKey("sk-test-key")
                .topK(10)
                .similarityThreshold(0.8f)
                .build();

        assertEquals(SemanticMemoryConfig.EmbeddingMode.GEMINI, config.getEmbeddingMode());
        assertEquals(SemanticMemoryConfig.StoreMode.IN_MEMORY, config.getStoreMode()); // still in memory
        assertEquals("sk-test-key", config.getGeminiApiKey());
        assertEquals("text-embedding-004", config.getGeminiEmbeddingModel());
        assertEquals(10, config.getTopK());
    }

    @Test
    void testOnnxPgVectorModeFromBuilder() {
        SemanticMemoryConfig config = SemanticMemoryConfig.builder()
                .userId("user-2")
                .onnxModelPath("/models/model.onnx")
                .onnxTokenizerPath("/models/tokenizer.json")
                .pgUrl("jdbc:postgresql://localhost/mydb")
                .pgUser("postgres")
                .pgPassword("password")
                .pgTable("memories")
                .pgDimension(384)
                .build();

        assertEquals(SemanticMemoryConfig.EmbeddingMode.ONNX, config.getEmbeddingMode());
        assertEquals(SemanticMemoryConfig.StoreMode.PGVECTOR, config.getStoreMode());
        assertEquals("/models/model.onnx", config.getOnnxModelPath());
        assertEquals("memories", config.getPgTable());
        assertEquals(384, config.getPgDimension());
    }

    @Test
    void testMissingUserIdThrows() {
        assertThrows(NullPointerException.class, () ->
                SemanticMemoryConfig.builder().build());
    }
}
