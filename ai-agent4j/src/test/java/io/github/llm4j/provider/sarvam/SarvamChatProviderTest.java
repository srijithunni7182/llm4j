package io.github.llm4j.provider.sarvam;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.exception.LLMException;
import io.github.llm4j.exception.ProviderException;
import io.github.llm4j.http.HttpClientWrapper;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.Message;
import java.util.Collections;
import okhttp3.Headers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SarvamChatProviderTest {

    @Mock private LLMConfig config;
    @Mock private HttpClientWrapper httpClient;

    private SarvamChatProvider provider;

    private static final String DUMMY_API_KEY = "test-api-key";
    private static final String DEFAULT_BASE_URL = "https://api.sarvam.ai";
    private static final String CHAT_ENDPOINT = "/v1/chat/completions";

    @BeforeEach
    void setUp() {
        when(config.getApiKey()).thenReturn(DUMMY_API_KEY);
        provider = new SarvamChatProvider(config, httpClient);
    }

    @Test
    void chat_withValidRequest_shouldReturnSuccessResponse() {
        // Arrange
        LLMRequest request =
                LLMRequest.builder()
                        .model("sarvam-m")
                        .messages(Collections.singletonList(Message.user("Hello")))
                        .build();

        String url = DEFAULT_BASE_URL + CHAT_ENDPOINT;
        String successResponse =
                "{\n"
                        + "  \"choices\": [\n"
                        + "    {\n"
                        + "      \"message\": {\n"
                        + "        \"content\": \"Hi there!\"\n"
                        + "      },\n"
                        + "      \"finish_reason\": \"STOP\"\n"
                        + "    }\n"
                        + "  ]\n"
                        + "}";

        when(httpClient.post(eq(url), any(String.class), any(Headers.class)))
                .thenReturn(successResponse);

        // Act
        LLMResponse response = provider.chat(request);

        // Assert
        assertNotNull(response);
        assertEquals("Hi there!", response.getContent());
        assertEquals(LLMResponse.FinishReason.STOP, response.getFinishReason());
    }

    @Test
    void chat_whenHttpClientThrowsException_shouldThrowProviderException() {
        // Arrange
        LLMRequest request =
                LLMRequest.builder()
                        .messages(Collections.singletonList(Message.user("test")))
                        .build();
        String url = DEFAULT_BASE_URL + CHAT_ENDPOINT;
        when(httpClient.post(eq(url), any(String.class), any(Headers.class)))
                .thenThrow(new LLMException("Network error"));

        // Act & Assert
        assertThrows(ProviderException.class, () -> provider.chat(request));
    }

    @Test
    void chatStream_shouldThrowUnsupportedOperationException() {
        // Arrange
        LLMRequest request =
                LLMRequest.builder()
                        .messages(Collections.singletonList(Message.user("test")))
                        .build();

        // Act & Assert
        assertThrows(UnsupportedOperationException.class, () -> provider.chatStream(request));
    }
}
