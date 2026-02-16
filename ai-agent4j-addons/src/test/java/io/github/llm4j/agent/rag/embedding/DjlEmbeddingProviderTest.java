package io.github.llm4j.agent.rag.embedding;

import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.TranslateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DjlEmbeddingProviderTest {

    @Mock
    private ZooModel<String, float[]> model;

    @Mock
    private Predictor<String, float[]> predictor;

    private DjlEmbeddingProvider embeddingProvider;

    @BeforeEach
    void setUp() {
        embeddingProvider = new DjlEmbeddingProvider(model, predictor, 384);
    }

    @Test
    void testEmbed() throws TranslateException {
        String text = "This is a test sentence.";
        float[] expectedEmbedding = new float[384];
        when(predictor.predict(text)).thenReturn(expectedEmbedding);

        float[] actualEmbedding = embeddingProvider.embed(text);

        assertArrayEquals(expectedEmbedding, actualEmbedding);
        verify(predictor, times(1)).predict(text);
    }

    @Test
    void testEmbedBatch() throws TranslateException {
        List<String> texts = Arrays.asList("First sentence.", "Second sentence.");
        List<float[]> expectedEmbeddings = Arrays.asList(new float[384], new float[384]);
        when(predictor.batchPredict(texts)).thenReturn(expectedEmbeddings);

        List<float[]> actualEmbeddings = embeddingProvider.embedBatch(texts);

        assertEquals(expectedEmbeddings.size(), actualEmbeddings.size());
        for (int i = 0; i < expectedEmbeddings.size(); i++) {
            assertArrayEquals(expectedEmbeddings.get(i), actualEmbeddings.get(i));
        }
        verify(predictor, times(1)).batchPredict(texts);
    }

    @Test
    void testGetDimensions() {
        assertEquals(384, embeddingProvider.getDimensions());
    }

    @Test
    void testClose() {
        embeddingProvider.close();
        verify(predictor, times(1)).close();
        verify(model, times(1)).close();
    }

    @Test
    void testEmbedFailure() throws TranslateException {
        String text = "This will fail.";
        when(predictor.predict(text)).thenThrow(new TranslateException("Embedding failed"));

        assertThrows(RuntimeException.class, () -> embeddingProvider.embed(text));
    }

    @Test
    void testEmbedBatchFailure() throws TranslateException {
        List<String> texts = Arrays.asList("This", "will", "fail.");
        when(predictor.batchPredict(texts)).thenThrow(new TranslateException("Batch embedding failed"));

        assertThrows(RuntimeException.class, () -> embeddingProvider.embedBatch(texts));
    }
}
