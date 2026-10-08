package io.github.llm4j.agent.rag.embedding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OnnxEmbeddingProviderTest {

    @Mock private OrtEnvironment mockEnv;
    @Mock private OrtSession mockSession;
    @Mock private HuggingFaceTokenizer mockTokenizer;
    @Mock private OrtSession.Result mockResult;
    @Mock private OnnxValue mockOnnxValue;
    @Mock private OnnxTensor mockOnnxTensor;

    private OnnxEmbeddingProvider embeddingProvider;
    private MockedStatic<OnnxTensor> onnxTensorMockedStatic;

    @BeforeEach
    void setUp() throws OrtException {
        // Mock the session's output info to extract dimensions
        Map<String, NodeInfo> outputInfo = new HashMap<>();
        long[] shape = {1, 1, 384}; // Example shape
        TensorInfo tensorInfo = mock(TensorInfo.class);
        when(tensorInfo.getShape()).thenReturn(shape);
        NodeInfo nodeInfo = mock(NodeInfo.class);
        when(nodeInfo.getInfo()).thenReturn(tensorInfo);
        outputInfo.put("output", nodeInfo);
        when(mockSession.getOutputInfo()).thenReturn(outputInfo);

        onnxTensorMockedStatic = mockStatic(OnnxTensor.class);
        onnxTensorMockedStatic
                .when(() -> OnnxTensor.createTensor(any(OrtEnvironment.class), any(long[][].class)))
                .thenReturn(mockOnnxTensor);

        embeddingProvider = new OnnxEmbeddingProvider(mockEnv, mockSession, mockTokenizer);
    }

    @AfterEach
    void tearDown() {
        onnxTensorMockedStatic.close();
    }

    @Test
    void testEmbed() throws OrtException {
        // Arrange
        String text = "hello world";
        Encoding encoding = mock(Encoding.class);
        when(encoding.getIds()).thenReturn(new long[] {101, 7592, 2088, 102});
        when(encoding.getAttentionMask()).thenReturn(new long[] {1, 1, 1, 1});
        when(encoding.getTypeIds()).thenReturn(new long[] {0, 0, 0, 0});
        when(mockTokenizer.encode(text)).thenReturn(encoding);

        float[][][] hiddenState = new float[1][4][384];
        // Fill with some dummy data
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 384; j++) {
                hiddenState[0][i][j] = i + j;
            }
        }

        when(mockResult.get(0)).thenReturn(mockOnnxValue);
        when(mockOnnxValue.getValue()).thenReturn(hiddenState);
        when(mockSession.run(anyMap())).thenReturn(mockResult);

        // Act
        float[] embedding = embeddingProvider.embed(text);

        // Assert
        assertNotNull(embedding);
        assertEquals(384, embedding.length);
    }

    @Test
    void testEmbedBatch() throws OrtException {
        // Arrange
        List<String> texts = List.of("hello", "world");
        Encoding encoding1 = mock(Encoding.class);
        when(encoding1.getIds()).thenReturn(new long[] {101, 7592, 102});
        when(encoding1.getAttentionMask()).thenReturn(new long[] {1, 1, 1});
        when(encoding1.getTypeIds()).thenReturn(new long[] {0, 0, 0});

        Encoding encoding2 = mock(Encoding.class);
        when(encoding2.getIds()).thenReturn(new long[] {101, 2088, 102});
        when(encoding2.getAttentionMask()).thenReturn(new long[] {1, 1, 1});
        when(encoding2.getTypeIds()).thenReturn(new long[] {0, 0, 0});

        when(mockTokenizer.encode("hello")).thenReturn(encoding1);
        when(mockTokenizer.encode("world")).thenReturn(encoding2);

        float[][][] hiddenState = new float[1][3][384];
        // Fill with some dummy data
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 384; j++) {
                hiddenState[0][i][j] = i + j;
            }
        }

        when(mockResult.get(0)).thenReturn(mockOnnxValue);
        when(mockOnnxValue.getValue()).thenReturn(hiddenState);
        when(mockSession.run(anyMap())).thenReturn(mockResult);

        // Act
        List<float[]> embeddings = embeddingProvider.embedBatch(texts);

        // Assert
        assertNotNull(embeddings);
        assertEquals(2, embeddings.size());
        assertEquals(384, embeddings.get(0).length);
        assertEquals(384, embeddings.get(1).length);
    }

    @Test
    void testGetDimensions() {
        assertEquals(384, embeddingProvider.getDimensions());
    }
}
