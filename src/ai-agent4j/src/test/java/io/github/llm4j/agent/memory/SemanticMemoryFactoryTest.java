package io.github.llm4j.agent.memory;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SemanticMemoryFactoryTest {

    @Test
    void testCreateInMemoryMode() {
        SemanticMemoryConfig config = SemanticMemoryConfig.inMemory("user-1");
        SemanticMemoryService service = SemanticMemoryFactory.create(config);
        assertNotNull(service);
    }

    @Test
    void testCreateInMemoryMode_saveAndRecall() {
        SemanticMemoryConfig config = SemanticMemoryConfig.inMemory("user-1");
        SemanticMemoryService service = SemanticMemoryFactory.create(config);

        // In-memory mode uses zero vectors — all cosine similarities will be 0.0 (undefined direction)
        // This is expected. The test just verifies no exception is thrown.
        service.saveFact("The user likes tea.");

        // With zero embeddings, cosine similarity is 0, so nothing should pass the 0.7 threshold
        var recalled = service.recallRelevantFacts("What does the user like to drink?", 5, 0.7f);
        // Will be empty because zero-vector similarity is 0.0 < threshold
        assertNotNull(recalled);
    }

    @Test
    void testCreateTool() {
        SemanticMemoryConfig config = SemanticMemoryConfig.inMemory("user-1");
        SemanticMemoryService service = SemanticMemoryFactory.create(config);
        var tool = SemanticMemoryFactory.createTool(service);
        assertNotNull(tool);
        assertEquals("save_memory_fact", tool.getName());
    }

    @Test
    void testOnnxMode_throwsWhenAddonsNotPresent() {
        // ONNX requires ai-agent4j-addons on the classpath
        // We're in the core module so it won't be on the classpath — expect a helpful error
        SemanticMemoryConfig config = SemanticMemoryConfig.builder()
                .userId("user-1")
                .onnxModelPath("/nonexistent/model.onnx")
                .onnxTokenizerPath("/nonexistent/tokenizer.json")
                .build();

        assertThrows(IllegalStateException.class, () -> SemanticMemoryFactory.create(config),
                "Should throw descriptive error when ai-agent4j-addons is missing");
    }
}
