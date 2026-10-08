package io.github.llm4j.provider.sarvam;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.http.HttpClientWrapper;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LanguageDetectionResponse;
import io.github.llm4j.model.TranscriptionRequest;
import java.io.File;
import java.util.Map;
import okhttp3.Headers;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The Sarvam fixes Loom relies on: language id endpoint, STT model, chat default model. */
class SarvamFixesTest {

    private static final String CHAT_OK =
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";

    @Test
    void languageIdentificationReadsLanguageAndScript() throws Exception {
        HttpClientWrapper http = mock(HttpClientWrapper.class);
        when(http.post(eq("https://api.sarvam.ai/text-lid"), anyString(), any(Headers.class)))
                .thenReturn("{\"request_id\":\"r\",\"language_code\":\"hi-IN\",\"script_code\":\"Deva\"}");
        LanguageDetectionResponse r = new SarvamTextProvider(LLMConfig.builder().apiKey("k").build(), http).detectLanguage("नमस्ते");
        assertEquals("hi-IN", r.getDetectedLanguageCode());
        assertEquals("Deva", r.getDetectedScript().orElseThrow());
    }

    @Test
    void chatUsesTheConfiguredModelWhenTheRequestNamesNone() throws Exception {
        HttpClientWrapper http = mock(HttpClientWrapper.class);
        when(http.post(anyString(), anyString(), any(Headers.class))).thenReturn(CHAT_OK);
        SarvamChatProvider chat = new SarvamChatProvider(LLMConfig.builder().apiKey("k").defaultModel("sarvam-m").build(), http);
        chat.chat(LLMRequest.builder().addUserMessage("hello").build());
        chat.chat(LLMRequest.builder().addUserMessage("hello").model("other").build());

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(http, times(2)).post(eq("https://api.sarvam.ai/v1/chat/completions"), body.capture(), any(Headers.class));
        assertTrue(body.getAllValues().get(0).contains("\"model\":\"sarvam-m\""), body.getAllValues().get(0));
        assertTrue(body.getAllValues().get(1).contains("\"model\":\"other\""));
    }

    @Test
    @SuppressWarnings("unchecked")
    void transcriptionSendsTheRequestedModel() throws Exception {
        HttpClientWrapper http = mock(HttpClientWrapper.class);
        when(http.postMultipart(anyString(), anyMap(), any(Headers.class))).thenReturn("{\"transcript\":\"namaste\"}");
        SarvamAudioProvider stt = new SarvamAudioProvider(LLMConfig.builder().apiKey("k").build(), http);
        File audio = File.createTempFile("clip", ".wav");
        audio.deleteOnExit();

        TranscriptionRequest request = TranscriptionRequest.builder().model("saarika:v2.5").languageCode("hi-IN").build();
        assertEquals("saarika:v2.5", request.getModel().orElseThrow());
        stt.transcribe(audio, request);
        stt.transcribe(audio, TranscriptionRequest.builder().build());

        ArgumentCaptor<Map<String, Object>> parts = ArgumentCaptor.forClass(Map.class);
        verify(http, times(2)).postMultipart(eq("https://api.sarvam.ai/speech-to-text"), parts.capture(), any(Headers.class));
        assertEquals("saarika:v2.5", parts.getAllValues().get(0).get("model"));
        assertEquals("hi-IN", parts.getAllValues().get(0).get("language_code"));
        assertEquals("saaras:v3", parts.getAllValues().get(1).get("model"));
    }
}
