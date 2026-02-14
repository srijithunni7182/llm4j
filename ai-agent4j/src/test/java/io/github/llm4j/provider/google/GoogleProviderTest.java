package io.github.llm4j.provider.google;

import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.exception.AuthenticationException;
import io.github.llm4j.exception.ContentBlockedException;
import io.github.llm4j.exception.ProviderException;
import io.github.llm4j.http.HttpClientWrapper;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import okhttp3.Headers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoogleProviderTest {

    private final PrintStream originalSystemErr = System.err;
    private final ByteArrayOutputStream systemErrContent = new ByteArrayOutputStream();

    @Mock
    private HttpClientWrapper mockHttpClient;

    private GoogleProvider provider;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // Capture stderr to verify no debug output slips through
        System.setErr(new PrintStream(systemErrContent));
    }

    @AfterEach
    void tearDown() {
        System.setErr(originalSystemErr);
    }
    
    private LLMConfig createValidConfig() {
        return LLMConfig.builder().apiKey("test-key").defaultModel("gemini-pro").build();
    }

    @Test
    void testConstructor_throwsException_whenApiKeyIsMissing() {
        LLMConfig config = LLMConfig.builder().build();
        assertThatThrownBy(() -> new GoogleProvider(config))
                .isInstanceOf(AuthenticationException.class)
                .hasMessage("Google API key is required");
    }

    @Test
    void testChat_successfulResponse() throws IOException {
        provider = new GoogleProvider(createValidConfig(), mockHttpClient);
        String mockResponse = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hello there\"}]}}], \"usageMetadata\": {\"promptTokenCount\": 1, \"candidatesTokenCount\": 2, \"totalTokenCount\": 3}}";
        when(mockHttpClient.post(anyString(), anyString(), any(Headers.class))).thenReturn(mockResponse);

        LLMResponse response = provider.chat(LLMRequest.builder().addUserMessage("Hi").build());

        assertThat(response.getContent()).isEqualTo("Hello there");
        assertThat(response.getTokenUsage().getTotalTokens()).isEqualTo(3);
        assertThat(systemErrContent.toString()).isEmpty();
    }

    @Test
    void testChat_handlesSystemMessageCorrectly() throws IOException {
        provider = new GoogleProvider(createValidConfig(), mockHttpClient);
        String mockResponse = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Yes, I am a bot\"}]}}]}";
        when(mockHttpClient.post(anyString(), anyString(), any(Headers.class))).thenReturn(mockResponse);

        provider.chat(LLMRequest.builder()
                .addSystemMessage("You are a helpful bot.")
                .addUserMessage("Are you a bot?")
                .build());

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mockHttpClient).post(anyString(), captor.capture(), any(Headers.class));
        
        assertThat(captor.getValue()).contains("You are a helpful bot.\\n\\nAre you a bot?");
    }
    
    @Test
    void testChat_throwsContentBlockedException_fromPromptFeedback() throws IOException {
        provider = new GoogleProvider(createValidConfig(), mockHttpClient);
        String mockResponse = "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}";
        when(mockHttpClient.post(anyString(), anyString(), any(Headers.class))).thenReturn(mockResponse);

        assertThatThrownBy(() -> provider.chat(LLMRequest.builder().addUserMessage("...").build()))
                .isInstanceOf(ContentBlockedException.class)
                .hasMessageContaining("Content blocked by safety filters: SAFETY");
    }

    @Test
    void testChat_throwsContentBlockedException_fromFinishReason() throws IOException {
        provider = new GoogleProvider(createValidConfig(), mockHttpClient);
        String mockResponse = "{\"candidates\":[{\"finishReason\":\"SAFETY\"}]}";
        when(mockHttpClient.post(anyString(), anyString(), any(Headers.class))).thenReturn(mockResponse);

        assertThatThrownBy(() -> provider.chat(LLMRequest.builder().addUserMessage("...").build()))
                .isInstanceOf(ContentBlockedException.class)
                .hasMessageContaining("Content blocked by safety filters.");
    }

    @Test
    void testChat_handlesMaxTokensFinishReason() throws IOException {
        provider = new GoogleProvider(createValidConfig(), mockHttpClient);
        String mockResponse = "{\"candidates\":[{\"finishReason\":\"MAX_TOKENS\",\"content\":{\"parts\":[]}}]}";
        when(mockHttpClient.post(anyString(), anyString(), any(Headers.class))).thenReturn(mockResponse);

        LLMResponse response = provider.chat(LLMRequest.builder().addUserMessage("...").build());

        assertThat(response.getContent()).contains("Response truncated: model hit token limit");
    }

    @Test
    void testListModels_throwsProviderException_onHttpError() throws IOException {
        provider = new GoogleProvider(createValidConfig(), mockHttpClient);
        when(mockHttpClient.get(anyString(), any())).thenThrow(new IOException("Network error"));

        assertThatThrownBy(() -> provider.listModels())
                .isInstanceOf(ProviderException.class)
                .hasMessage("Failed to list models");
    }

    @Test
    void testGetFirstAvailableModel_successful() throws IOException {
        provider = new GoogleProvider(createValidConfig(), mockHttpClient);
        String mockResponse = "{\"models\":[{\"name\":\"models/gemini-1.0-pro\",\"supportedGenerationMethods\":[\"generateContent\"]},{\"name\":\"models/text-bison-001\"}]}";
        when(mockHttpClient.get(anyString(), any())).thenReturn(mockResponse);

        String model = provider.getFirstAvailableModel();
        assertThat(model).isEqualTo("gemini-1.0-pro");
    }
}
