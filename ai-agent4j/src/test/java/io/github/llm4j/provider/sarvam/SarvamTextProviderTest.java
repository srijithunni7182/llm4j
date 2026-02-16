package io.github.llm4j.provider.sarvam;

import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.exception.LLMException;
import io.github.llm4j.exception.ProviderException;
import io.github.llm4j.http.HttpClientWrapper;
import io.github.llm4j.model.*;
import okhttp3.Headers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SarvamTextProviderTest {

    @Mock
    private LLMConfig config;
    @Mock
    private HttpClientWrapper httpClient;

    private SarvamTextProvider provider;

    private static final String DUMMY_API_KEY = "test-api-key";
    private static final String DEFAULT_BASE_URL = "https://api.sarvam.ai";

    @BeforeEach
    void setUp() {
        when(config.getApiKey()).thenReturn(DUMMY_API_KEY);
        // Use the package-private constructor to inject the mock client
        provider = new SarvamTextProvider(config, httpClient);
    }

    // Translation Tests
    @Test
    void translate_withValidRequest_shouldReturnSuccessResponse() throws Exception {
        TranslationRequest request = TranslationRequest.builder().text("Hello").targetLanguageCode("hi-IN").build();
        String url = DEFAULT_BASE_URL + "/translate";
        String successResponse = "{\"translated_text\": \"नमस्ते\"}";
        when(httpClient.post(eq(url), any(String.class), any(Headers.class))).thenReturn(successResponse);

        TranslationResponse response = provider.translate(request);

        assertNotNull(response);
        assertEquals("नमस्ते", response.getTranslatedText());
    }

    @Test
    void translate_whenHttpClientThrowsException_shouldThrowProviderException() {
        TranslationRequest request = TranslationRequest.builder().text("Hello").targetLanguageCode("hi-IN").build();
        String url = DEFAULT_BASE_URL + "/translate";
        when(httpClient.post(eq(url), any(String.class), any(Headers.class)))
                .thenThrow(new LLMException("Network error", new IOException()));

        assertThrows(ProviderException.class, () -> provider.translate(request));
    }

    // Transliteration Tests
    @Test
    void transliterate_withValidRequest_shouldReturnSuccessResponse() throws Exception {
        TransliterationRequest request = TransliterationRequest.builder().text("namaste").targetLanguageCode("hi-IN").build();
        String url = DEFAULT_BASE_URL + "/transliterate";
        String successResponse = "{\"transliterated_text\": \"नमस्ते\"}";
        when(httpClient.post(eq(url), any(String.class), any(Headers.class))).thenReturn(successResponse);

        TransliterationResponse response = provider.transliterate(request);

        assertNotNull(response);
        assertEquals("नमस्ते", response.getTransliteratedText());
    }

    @Test
    void transliterate_whenHttpClientThrowsException_shouldThrowProviderException() {
        TransliterationRequest request = TransliterationRequest.builder().text("namaste").targetLanguageCode("hi-IN").build();
        String url = DEFAULT_BASE_URL + "/transliterate";
        when(httpClient.post(eq(url), any(String.class), any(Headers.class)))
                .thenThrow(new LLMException("Network error", new IOException()));

        assertThrows(ProviderException.class, () -> provider.transliterate(request));
    }

    // Language Detection Tests
    @Test
    void detectLanguage_withValidText_shouldReturnSuccessResponse() throws Exception {
        String text = "नमस्ते";
        String url = DEFAULT_BASE_URL + "/detect-language";
        String successResponse = "{\"language_code\": \"hi-IN\", \"confidence\": 0.99}";
        when(httpClient.post(eq(url), any(String.class), any(Headers.class))).thenReturn(successResponse);

        LanguageDetectionResponse response = provider.detectLanguage(text);

        assertNotNull(response);
        assertEquals("hi-IN", response.getDetectedLanguageCode());
        assertTrue(response.getConfidenceScore().isPresent());
        assertEquals(0.99, response.getConfidenceScore().get().doubleValue());
    }

    @Test
    void detectLanguage_whenHttpClientThrowsException_shouldThrowProviderException() {
        String text = "नमस्ते";
        String url = DEFAULT_BASE_URL + "/detect-language";
        when(httpClient.post(eq(url), any(String.class), any(Headers.class)))
                .thenThrow(new LLMException("Network error", new IOException()));

        assertThrows(ProviderException.class, () -> provider.detectLanguage(text));
    }
}
