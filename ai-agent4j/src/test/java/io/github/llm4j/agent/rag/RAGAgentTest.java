package io.github.llm4j.agent.rag;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.rag.document.Document;
import io.github.llm4j.agent.rag.document.DocumentChunk;
import io.github.llm4j.agent.rag.embedding.EmbeddingProvider;
import io.github.llm4j.agent.rag.store.VectorStore;
import io.github.llm4j.agent.rag.store.VectorStore.SearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RAGAgentTest {

    @Mock
    private ReActAgent mockAgent;
    @Mock
    private VectorStore mockVectorStore;
    @Mock
    private EmbeddingProvider mockEmbeddingProvider;

    private RAGAgent ragAgent;

    @BeforeEach
    void setUp() {
        ragAgent = RAGAgent.builder()
                .agent(mockAgent)
                .vectorStore(mockVectorStore)
                .embeddingProvider(mockEmbeddingProvider)
                .topK(3)
                .build();
    }

    @Test
    void run_shouldRetrievalAndAugmentPrompt() {
        String question = "What is the capital of France?";
        float[] queryEmbedding = new float[]{0.1f, 0.2f};
        
        // Mock embedding
        when(mockEmbeddingProvider.embed(question)).thenReturn(queryEmbedding);

        // Mock retrieval
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("content", "Paris is the capital of France.");
        SearchResult result = new SearchResult("doc1", 0.9f, metadata);
        when(mockVectorStore.search(queryEmbedding, 3)).thenReturn(List.of(result));

        // Mock agent execution
        AgentResult expectedResult = AgentResult.builder()
                .finalAnswer("The capital of France is Paris.")
                .build();
        when(mockAgent.run(anyString())).thenReturn(expectedResult);

        // Act
        AgentResult actualResult = ragAgent.run(question);

        // Assert
        assertThat(actualResult).isEqualTo(expectedResult);
        
        verify(mockEmbeddingProvider).embed(question);
        verify(mockVectorStore).search(queryEmbedding, 3);
        verify(mockAgent).run(argThat(prompt -> 
            prompt.contains("Paris is the capital of France.") &&
            prompt.contains("Question: " + question)
        ));
    }

    @Test
    void run_shouldHandleEmptyRetrieval() {
        String question = "Unknown question";
        float[] queryEmbedding = new float[]{0.1f, 0.2f};

        when(mockEmbeddingProvider.embed(question)).thenReturn(queryEmbedding);
        when(mockVectorStore.search(queryEmbedding, 3)).thenReturn(Collections.emptyList());
        
        AgentResult expectedResult = AgentResult.builder()
                .finalAnswer("I don't know.")
                .build();
        when(mockAgent.run(question)).thenReturn(expectedResult); // Should pass original question if no context

        AgentResult actualResult = ragAgent.run(question);

        verify(mockAgent).run(question);
    }

    @Test
    void addDocument_shouldChunkEmbedAndStore() {
        Document document = mock(Document.class);
        when(document.getId()).thenReturn("doc1");
        
        DocumentChunk chunk1 = DocumentChunk.builder()
            .id("chunk1")
            .documentId("doc1")
            .content("Content 1")
            .startIndex(0)
            .endIndex(0)
            .build();
        DocumentChunk chunk2 = DocumentChunk.builder()
            .id("chunk2")
            .documentId("doc1")
            .content("Content 2")
            .startIndex(1)
            .endIndex(1)
            .build();
        List<DocumentChunk> chunks = List.of(chunk1, chunk2);
        
        when(document.getChunks()).thenReturn(chunks);
        
        float[] embedding1 = new float[]{0.1f};
        float[] embedding2 = new float[]{0.2f};
        when(mockEmbeddingProvider.embedBatch(anyList())).thenReturn(List.of(embedding1, embedding2));

        ragAgent.addDocument(document);

        verify(mockEmbeddingProvider).embedBatch(List.of("Content 1", "Content 2"));
        verify(mockVectorStore).add(eq("chunk1"), eq(embedding1), anyMap());
        verify(mockVectorStore).add(eq("chunk2"), eq(embedding2), anyMap());
    }
}
