package io.github.llm4j.provider.ollama;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.exception.ProviderException;
import io.github.llm4j.http.HttpClientWrapper;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.io.IOException;
import okhttp3.Headers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class OllamaProviderTest {

    @Mock private HttpClientWrapper mockHttpClient;

    private OllamaProvider provider;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    private LLMConfig createValidConfig() {
        return LLMConfig.builder().defaultModel("gemma").build();
    }

    @Test
    void testChat_successfulResponse() throws IOException {
        provider = new OllamaProvider(createValidConfig(), mockHttpClient, new com.fasterxml.jackson.databind.ObjectMapper());
        String mockResponse = "{\"model\":\"gemma\",\"created_at\":\"2023-08-04T19:22:45.499127Z\",\"message\":{\"role\":\"assistant\",\"content\":\"The sky is blue\"},\"done\":true,\"prompt_eval_count\":12,\"eval_count\":55}";
        when(mockHttpClient.post(anyString(), anyString(), any(Headers.class))).thenReturn(mockResponse);

        LLMResponse response = provider.chat(LLMRequest.builder().addUserMessage("why is the sky blue?").build());

        assertThat(response.getContent()).isEqualTo("The sky is blue");
        assertThat(response.getTokenUsage().getTotalTokens()).isEqualTo(67);
    }

    @Test
    void testChat_handlesErrorResponse() throws IOException {
        provider = new OllamaProvider(createValidConfig(), mockHttpClient, new com.fasterxml.jackson.databind.ObjectMapper());
        String mockResponse = "{\"error\":\"model 'gemma' not found\"}";
        when(mockHttpClient.post(anyString(), anyString(), any(Headers.class))).thenReturn(mockResponse);

        assertThatThrownBy(() -> provider.chat(LLMRequest.builder().addUserMessage("Hi").build()))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("model 'gemma' not found");
    }

    @Test
    void testListModels_successful() throws IOException {
        provider = new OllamaProvider(createValidConfig(), mockHttpClient, new com.fasterxml.jackson.databind.ObjectMapper());
        String mockResponse = "{\"models\":[{\"name\":\"gemma:latest\"},{\"name\":\"llama3:8b\"}]}";
        when(mockHttpClient.get(anyString(), any())).thenReturn(mockResponse);

        String[] models = provider.listModels();
        assertThat(models).containsExactly("gemma:latest", "llama3:8b");
    }

    @Test
    void testGetFirstAvailableModel_successful() throws IOException {
        provider = new OllamaProvider(createValidConfig(), mockHttpClient, new com.fasterxml.jackson.databind.ObjectMapper());
        String mockResponse = "{\"models\":[{\"name\":\"llama3:8b\"},{\"name\":\"gemma:latest\"}]}";
        when(mockHttpClient.get(anyString(), any())).thenReturn(mockResponse);

        String model = provider.getFirstAvailableModel();
        assertThat(model).isEqualTo("gemma:latest"); // Should prioritize gemma over llama3
    }
}
