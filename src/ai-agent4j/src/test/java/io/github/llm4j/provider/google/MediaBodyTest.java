package io.github.llm4j.provider.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.exception.InvalidRequestException;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.Media;
import io.github.llm4j.model.MediaKind;
import io.github.llm4j.model.MediaPolicy;
import io.github.llm4j.model.Message;
import io.github.llm4j.model.TinyMedia;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Media on {@code generateContent} (docs/specs/31-multimodal-input R11). Confirmed against the real
 * API on 2026-11-06 with {@code gemini-2.5-flash}: an {@code inline_data} part with a {@code
 * mime_type} and base64 {@code data}, before the text part, for pictures, PDFs, WAV and MP3 sound
 * and MP4 video.
 */
class MediaBodyTest {

    private static final String REPLY =
            "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"STOP\"}],"
                    + "\"usageMetadata\":{\"promptTokenCount\":1,\"candidatesTokenCount\":1,\"totalTokenCount\":2}}";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final MediaPolicy POLICY = MediaPolicy.defaults();

    private MockWebServer server;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
        server.enqueue(
                new MockResponse().setHeader("Content-Type", "application/json").setBody(REPLY));
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    private GoogleProvider provider() {
        return new GoogleProvider(
                LLMConfig.builder()
                        .apiKey("test-key")
                        .baseUrl(server.url("/v1beta").toString().replaceAll("/$", ""))
                        .defaultModel("gemini-2.5-flash")
                        .build());
    }

    private static LLMRequest ask(String text, Media... media) {
        // five items in one message is more than the default policy allows, so say so
        return LLMRequest.builder()
                .mediaPolicy(MediaPolicy.builder().maxItemsPerMessage(5).build())
                .addMessage(Message.user(text, List.of(media)))
                .build();
    }

    private JsonNode send(LLMRequest request) throws Exception {
        provider().chat(request);
        return JSON.readTree(server.takeRequest().getBody().readUtf8());
    }

    @Test
    void everyKindIsAnInlineDataPartBeforeTheText() throws Exception {
        JsonNode parts =
                send(ask(
                                "look",
                                Media.of(MediaKind.IMAGE, TinyMedia.png(), "a.png", POLICY),
                                Media.of(MediaKind.DOCUMENT, TinyMedia.pdf(), "a.pdf", POLICY),
                                Media.of(MediaKind.AUDIO, TinyMedia.wav(), "a.wav", POLICY),
                                Media.of(MediaKind.AUDIO, TinyMedia.mp3(), "a.mp3", POLICY),
                                Media.of(MediaKind.VIDEO, TinyMedia.mp4(), "a.mp4", POLICY)))
                        .at("/contents/0/parts");

        assertThat(parts).hasSize(6);
        String[] types = {"image/png", "application/pdf", "audio/wav", "audio/mpeg", "video/mp4"};
        for (int i = 0; i < types.length; i++) {
            assertThat(parts.at("/" + i + "/inline_data/mime_type").asText()).isEqualTo(types[i]);
            assertThat(parts.at("/" + i + "/inline_data/data").asText()).isNotBlank();
        }
        assertThat(parts.at("/0/inline_data/data").asText())
                .isEqualTo(TinyMedia.base64(TinyMedia.png()));
        assertThat(parts.at("/5/text").asText()).isEqualTo("look");
    }

    @Test
    void anEmptyTextPartIsLeftOut() throws Exception {
        JsonNode parts =
                send(ask("", Media.of(MediaKind.IMAGE, TinyMedia.png(), "a.png", POLICY)))
                        .at("/contents/0/parts");

        assertThat(parts).hasSize(1);
        assertThat(parts.at("/0").has("inline_data")).isTrue();
    }

    @Test
    void aTextOnlyRequestIsUnchanged() throws Exception {
        JsonNode parts = send(ask("hi")).at("/contents/0/parts");

        assertThat(parts).hasSize(1);
        assertThat(parts.at("/0/text").asText()).isEqualTo("hi");
    }

    @Test
    void refusesMoreInlineBytesThanTheApiTakesBeforeAnyCall() {
        // two 11 MiB pictures: each within the library's limit, together over Gemini's 20 MiB
        byte[] big = java.util.Arrays.copyOf(TinyMedia.png(), 11 * 1024 * 1024);
        Media a = Media.of(MediaKind.IMAGE, big, "a.png", POLICY);
        Media b = Media.of(MediaKind.IMAGE, big, "b.png", POLICY);

        assertThatThrownBy(() -> provider().chat(ask("both", a, b)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("20")
                .hasMessageContaining("inline");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void refusesMediaOnAModelThatCannotTakeIt() {
        GoogleProvider tts =
                new GoogleProvider(
                        LLMConfig.builder()
                                .apiKey("test-key")
                                .baseUrl(server.url("/v1beta").toString().replaceAll("/$", ""))
                                .defaultModel("gemini-2.5-flash-preview-tts")
                                .build());

        assertThatThrownBy(
                        () ->
                                tts.chat(
                                        ask(
                                                "x",
                                                Media.of(
                                                        MediaKind.IMAGE,
                                                        TinyMedia.png(),
                                                        "a.png",
                                                        POLICY))))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("does not accept image");
        assertThat(server.getRequestCount()).isZero();
    }
}
