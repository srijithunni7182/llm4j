package io.github.llm4j.provider.ollama;

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
 * Pictures on Ollama's chat API (docs/specs/31-multimodal-input R11): a base64 {@code images} array
 * on the message, for vision models only. Written from Ollama's documentation; not confirmed
 * against a running server (VERIFICATION §7).
 */
class MediaBodyTest {

    private static final String REPLY =
            "{\"model\":\"gemma3:4b\",\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                    + "\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":1,\"eval_count\":1}";
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

    private OllamaProvider provider(String model) {
        return new OllamaProvider(
                LLMConfig.builder()
                        .baseUrl(server.url("/api").toString().replaceAll("/$", ""))
                        .defaultModel(model)
                        .build());
    }

    private static LLMRequest ask(String text, Media... media) {
        return LLMRequest.builder().addMessage(Message.user(text, List.of(media))).build();
    }

    @Test
    void aPictureIsABase64EntryInTheMessagesImagesArray() throws Exception {
        provider("gemma3:4b")
                .chat(
                        ask(
                                "What colour?",
                                Media.of(MediaKind.IMAGE, TinyMedia.png(), "a.png", POLICY)));

        JsonNode message =
                JSON.readTree(server.takeRequest().getBody().readUtf8()).at("/messages/0");

        assertThat(message.get("content").asText()).isEqualTo("What colour?");
        assertThat(message.get("images")).hasSize(1);
        assertThat(message.at("/images/0").asText()).isEqualTo(TinyMedia.base64(TinyMedia.png()));
    }

    @Test
    void aTextOnlyMessageHasNoImagesField() throws Exception {
        provider("llama3.1:8b").chat(ask("hi"));

        assertThat(
                        JSON.readTree(server.takeRequest().getBody().readUtf8())
                                .at("/messages/0")
                                .has("images"))
                .isFalse();
    }

    @Test
    void refusesAPictureOnAModelThatIsNotAVisionModelBeforeAnyCall() {
        Media photo = Media.of(MediaKind.IMAGE, TinyMedia.png(), "a.png", POLICY);

        assertThatThrownBy(() -> provider("llama3.1:8b").chat(ask("x", photo)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("does not accept image");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void refusesSoundAndVideoBecauseOllamaTakesOnlyPictures() {
        Media clip = Media.of(MediaKind.VIDEO, TinyMedia.mp4(), "a.mp4", POLICY);

        assertThatThrownBy(() -> provider("gemma3:4b").chat(ask("x", clip)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("does not accept video");
        assertThat(server.getRequestCount()).isZero();
    }
}
