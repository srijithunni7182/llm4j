package io.github.llm4j.agent.rag.embedding;

import io.github.llm4j.config.LLMConfig;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GeminiEmbeddingProviderTest {

    private MockWebServer mockWebServer;
    private GeminiEmbeddingProvider provider;
    private LLMConfig config;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        config = mock(LLMConfig.class);
        when(config.getApiKey()).thenReturn("test-api-key");
        // Redirect base URL to MockWebServer
        when(config.getBaseUrl()).thenReturn(mockWebServer.url("/").toString());
        when(config.getConnectTimeout()).thenReturn(java.time.Duration.ofSeconds(5));
        when(config.getTimeout()).thenReturn(java.time.Duration.ofSeconds(5));

        provider = new GeminiEmbeddingProvider(config, "test-model", new OkHttpClient());
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    void embed_shouldReturnEmbedding_whenResponseIsSuccess() throws InterruptedException {
        // Mock successful JSON response from Gemini API
        String jsonResponse = "{\n" +
                "  \"embedding\": {\n" +
                "    \"values\": [\n" +
                "      0.1,\n" +
                "      0.2,\n" +
                "      0.3\n" +
                "    ]\n" +
                "  }\n" +
                "}";

        mockWebServer.enqueue(new MockResponse()
                .setBody(jsonResponse)
                .addHeader("Content-Type", "application/json"));

        float[] embedding = provider.embed("hello world");

        assertThat(embedding).hasSize(3);
        assertThat(embedding[0]).isEqualTo(0.1f);
        assertThat(embedding[1]).isEqualTo(0.2f);
        assertThat(embedding[2]).isEqualTo(0.3f);

        RecordedRequest request = mockWebServer.takeRequest();
        assertThat(request.getPath()).contains("/models/test-model:embedContent");
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getBody().readUtf8()).contains("hello world");
    }

    @Test
    void embed_shouldThrowException_whenApiReturnsError() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(400)
                .setBody("{\"error\": \"Bad Request\"}"));

        assertThatThrownBy(() -> provider.embed("test"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to generate embedding");
    }

    @Test
    void embed_shouldThrowException_whenResponseIsMalformed() {
        // Missing "values"
        String jsonResponse = "{\n" +
                "  \"embedding\": {}\n" +
                "}";

        mockWebServer.enqueue(new MockResponse()
                .setBody(jsonResponse)
                .addHeader("Content-Type", "application/json"));

        assertThatThrownBy(() -> provider.embed("test"))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(IOException.class) // Underlying cause
                .hasMessageContaining("Failed to generate embedding");
    }

    @Test
    void embedBatch_shouldCallEmbedForEachText() {
        String jsonResponse = "{\n" +
                "  \"embedding\": {\n" +
                "    \"values\": [0.1]\n" +
                "  }\n" +
                "}";

        // Enqueue two responses for two texts
        mockWebServer.enqueue(new MockResponse().setBody(jsonResponse));
        mockWebServer.enqueue(new MockResponse().setBody(jsonResponse));

        List<float[]> embeddings = provider.embedBatch(List.of("text1", "text2"));

        assertThat(embeddings).hasSize(2);
        assertThat(mockWebServer.getRequestCount()).isEqualTo(2);
    }
    
    @Test
    void getDimensions_shouldReturn768() {
        assertThat(provider.getDimensions()).isEqualTo(768);
    }
}
