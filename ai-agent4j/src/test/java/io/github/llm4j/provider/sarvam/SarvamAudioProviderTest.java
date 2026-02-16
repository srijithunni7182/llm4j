package io.github.llm4j.provider.sarvam;

import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.exception.AuthenticationException;
import io.github.llm4j.exception.LLMException;
import io.github.llm4j.exception.ProviderException;
import io.github.llm4j.http.HttpClientWrapper;
import io.github.llm4j.model.TranscriptionRequest;
import io.github.llm4j.model.TranscriptionResponse;
import okhttp3.Headers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SarvamAudioProviderTest {

    @Mock
    private LLMConfig config;
    @Mock
    private HttpClientWrapper httpClient;

    private SarvamAudioProvider provider;
    private File dummyAudioFile;

    @TempDir
    Path tempDir;

    private static final String DUMMY_API_KEY = "test-api-key";
    private static final String DEFAULT_BASE_URL = "https://api.sarvam.ai";
    private static final String TRANSCRIBE_ENDPOINT = "/speech-to-text";
    private static final String TRANSLATE_ENDPOINT = "/speech-to-text-translate";

    @BeforeEach
    void setUp() throws IOException {
        when(config.getApiKey()).thenReturn(DUMMY_API_KEY);
        provider = new SarvamAudioProvider(config, httpClient);
        dummyAudioFile = Files.createFile(tempDir.resolve("test.wav")).toFile();
    }

    @Test
    void transcribe_withValidRequest_shouldReturnSuccessResponse() {
        // Arrange
        TranscriptionRequest request = TranscriptionRequest.builder().languageCode("en-US").build();
        String url = DEFAULT_BASE_URL + TRANSCRIBE_ENDPOINT;
        String successResponse = "{\"transcript\": \"Hello world\", \"language_code\": \"en-US\"}";
        when(httpClient.postMultipart(eq(url), any(Map.class), any(Headers.class))).thenReturn(successResponse);

        // Act
        TranscriptionResponse response = provider.transcribe(dummyAudioFile, request);

        // Assert
        assertNotNull(response);
        assertEquals("Hello world", response.getText());
        assertEquals("en-US", response.getLanguageCode());
    }

    @Test
    void transcribe_whenHttpClientThrowsException_shouldThrowProviderException() {
        // Arrange
        TranscriptionRequest request = TranscriptionRequest.builder().build();
        String url = DEFAULT_BASE_URL + TRANSCRIBE_ENDPOINT;
        when(httpClient.postMultipart(eq(url), any(Map.class), any(Headers.class))).thenThrow(new LLMException("Network error"));

        // Act & Assert
        assertThrows(ProviderException.class, () -> provider.transcribe(dummyAudioFile, request));
    }

    @Test
    void transcribe_withErrorInResponse_shouldThrowProviderException() {
        // Arrange
        TranscriptionRequest request = TranscriptionRequest.builder().build();
        String url = DEFAULT_BASE_URL + TRANSCRIBE_ENDPOINT;
        String errorResponse = "{\"error\": \"Invalid audio file\"}";
        when(httpClient.postMultipart(eq(url), any(Map.class), any(Headers.class))).thenReturn(errorResponse);

        // Act & Assert
        ProviderException exception = assertThrows(ProviderException.class, () -> provider.transcribe(dummyAudioFile, request));
        assertTrue(exception.getMessage().contains("Invalid audio file"));
    }
}
