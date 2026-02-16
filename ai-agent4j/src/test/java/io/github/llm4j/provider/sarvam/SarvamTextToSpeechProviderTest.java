package io.github.llm4j.provider.sarvam;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.exception.LLMException;
import io.github.llm4j.exception.ProviderException;
import io.github.llm4j.http.HttpClientWrapper;
import io.github.llm4j.model.TextToSpeechRequest;
import io.github.llm4j.model.TextToSpeechResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import okhttp3.Headers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SarvamTextToSpeechProviderTest {

    @Mock private LLMConfig config;
    @Mock private HttpClientWrapper httpClient;

    private SarvamTextToSpeechProvider provider;

    private static final String DUMMY_API_KEY = "test-api-key";
    private static final String DEFAULT_BASE_URL = "https://api.sarvam.ai";
    private static final String TTS_ENDPOINT = "/text-to-speech";

    @BeforeEach
    void setUp() {
        when(config.getApiKey()).thenReturn(DUMMY_API_KEY);
        provider = new SarvamTextToSpeechProvider(config, httpClient);
    }

    @Test
    void generateSpeech_withValidRequest_shouldReturnSuccessResponse() {
        // Arrange
        TextToSpeechRequest request = TextToSpeechRequest.builder().text("Hello world").build();

        String url = DEFAULT_BASE_URL + TTS_ENDPOINT;
        byte[] audioBytes = "audio data".getBytes(StandardCharsets.UTF_8);
        String base64Audio = Base64.getEncoder().encodeToString(audioBytes);
        String successResponse = "{\"audios\": [{\"audio_b64\": \"" + base64Audio + "\"}]}";

        when(httpClient.post(eq(url), any(String.class), any(Headers.class)))
                .thenReturn(successResponse);

        // Act
        TextToSpeechResponse response = provider.generateSpeech(request);

        // Assert
        assertNotNull(response);
        assertArrayEquals(audioBytes, response.getAudioData());
        assertEquals(Optional.of("audio/wav"), response.getContentType());
    }

    @Test
    void generateSpeech_whenHttpClientThrowsException_shouldThrowProviderException() {
        // Arrange
        TextToSpeechRequest request = TextToSpeechRequest.builder().text("test").build();
        String url = DEFAULT_BASE_URL + TTS_ENDPOINT;
        when(httpClient.post(eq(url), any(String.class), any(Headers.class)))
                .thenThrow(new LLMException("Network error"));

        // Act & Assert
        assertThrows(ProviderException.class, () -> provider.generateSpeech(request));
    }
}
