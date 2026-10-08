package io.github.llm4j.agent.memory;

import io.github.llm4j.agent.rag.embedding.EmbeddingProvider;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class SemanticMemoryServiceTest {

    @Mock
    private EmbeddingProvider embeddingProvider;

    @Mock
    private VectorStore vectorStore;

    private SemanticMemoryService service;
    private final float[] dummyEmbedding = new float[]{0.1f, 0.2f, 0.3f};

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        service = new SemanticMemoryService(embeddingProvider, vectorStore, "user-123");
        when(embeddingProvider.embed(anyString())).thenReturn(dummyEmbedding);
    }

    @Test
    void testSaveFact() {
        service.saveFact("The user is a vegetarian.");
        verify(embeddingProvider).embed("The user is a vegetarian.");
        verify(vectorStore).add(anyString(), eq(dummyEmbedding), argThat(metadata ->
                "The user is a vegetarian.".equals(metadata.get("fact")) &&
                "user-123".equals(metadata.get("userId"))
        ));
    }

    @Test
    void testSaveFact_emptyString() {
        service.saveFact("  ");
        verifyNoInteractions(embeddingProvider);
        verifyNoInteractions(vectorStore);
    }

    @Test
    void testRecallRelevantFacts() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("fact", "The user loves Java.");
        metadata.put("userId", "user-123");

        SearchResult result = new SearchResult("id-1", 0.9f, metadata);
        when(vectorStore.search(any(float[].class), anyInt(), anyMap())).thenReturn(List.of(result));

        List<String> facts = service.recallRelevantFacts("What programming language?", 3, 0.7f);
        assertEquals(1, facts.size());
        assertEquals("The user loves Java.", facts.get(0));
    }

    @Test
    void testRecallRelevantFacts_belowThreshold() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("fact", "The user loves Java.");

        SearchResult result = new SearchResult("id-1", 0.5f, metadata); // below 0.7 threshold
        when(vectorStore.search(any(float[].class), anyInt(), anyMap())).thenReturn(List.of(result));

        List<String> facts = service.recallRelevantFacts("What programming language?", 3, 0.7f);
        assertTrue(facts.isEmpty(), "Low-similarity facts should be filtered out");
    }

    @Test
    void testRecallRelevantFacts_vectorStoreFailure() {
        when(vectorStore.search(any(float[].class), anyInt(), anyMap()))
                .thenThrow(new RuntimeException("DB down"));

        // Should fail gracefully and return empty list
        List<String> facts = service.recallRelevantFacts("some query", 3, 0.7f);
        assertTrue(facts.isEmpty());
    }
}
