package io.github.llm4j.provider.anthropic;

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
import io.github.llm4j.model.PromptCache;
import io.github.llm4j.model.TinyMedia;
import java.util.List;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

/**
 * Images and PDFs on the Messages API (docs/specs/31-multimodal-input R11). Shapes confirmed
 * against the real API on 2026-11-06: an {@code image} or {@code document} block with a base64
 * {@code source}, before the text; {@code audio} and {@code video} blocks are rejected by the API.
 */
class MediaBodyTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final MediaPolicy POLICY = MediaPolicy.defaults();
    private static final String MODEL = "claude-sonnet-5-5";

    private static JsonNode body(LLMRequest request) throws Exception {
        AnthropicProvider provider =
                new AnthropicProvider(LLMConfig.builder().apiKey("test-key").build());
        return JSON.readTree(provider.body(request, MODEL, false));
    }

    private static Media png() {
        return Media.of(MediaKind.IMAGE, TinyMedia.png(), "a.png", POLICY);
    }

    private static Media pdf() {
        return Media.of(MediaKind.DOCUMENT, TinyMedia.pdf(), "a.pdf", POLICY);
    }

    private static LLMRequest ask(String text, Media... media) {
        return LLMRequest.builder().addMessage(Message.user(text, List.of(media))).build();
    }

    @Test
    void anImageIsABase64BlockBeforeTheText() throws Exception {
        JsonNode content = body(ask("What colour?", png())).at("/messages/0/content");

        assertThat(content).hasSize(2);
        assertThat(content.at("/0/type").asText()).isEqualTo("image");
        assertThat(content.at("/0/source/type").asText()).isEqualTo("base64");
        assertThat(content.at("/0/source/media_type").asText()).isEqualTo("image/png");
        assertThat(content.at("/0/source/data").asText())
                .isEqualTo(TinyMedia.base64(TinyMedia.png()));
        assertThat(content.at("/1/type").asText()).isEqualTo("text");
        assertThat(content.at("/1/text").asText()).isEqualTo("What colour?");
    }

    @Test
    void aPdfIsADocumentBlock() throws Exception {
        JsonNode block = body(ask("Total?", pdf())).at("/messages/0/content/0");

        assertThat(block.get("type").asText()).isEqualTo("document");
        assertThat(block.at("/source/media_type").asText()).isEqualTo("application/pdf");
        assertThat(block.at("/source/data").asText()).isEqualTo(TinyMedia.base64(TinyMedia.pdf()));
    }

    @Test
    void severalItemsKeepTheOrderTheCallerGave() throws Exception {
        JsonNode content = body(ask("Compare", png(), pdf(), png())).at("/messages/0/content");

        assertThat(content).hasSize(4);
        assertThat(content.at("/0/type").asText()).isEqualTo("image");
        assertThat(content.at("/1/type").asText()).isEqualTo("document");
        assertThat(content.at("/2/type").asText()).isEqualTo("image");
        assertThat(content.at("/3/type").asText()).isEqualTo("text");
    }

    @Test
    void anEmptyTextBlockIsLeftOutBecauseTheApiRefusesIt() throws Exception {
        JsonNode content = body(ask("", png())).at("/messages/0/content");

        assertThat(content).hasSize(1);
        assertThat(content.at("/0/type").asText()).isEqualTo("image");
    }

    @Test
    void aTextOnlyMessageIsStillAPlainString() throws Exception {
        assertThat(body(ask("hi")).at("/messages/0/content").isTextual()).isTrue();
    }

    @Test
    void aBreakpointOnAMessageWithMediaMarksTheLastBlock() throws Exception {
        LLMRequest request =
                ask("What colour?", png()).toBuilder()
                        .promptCache(PromptCache.builder().throughMessage(0).build())
                        .build();

        JsonNode content = body(request).at("/messages/0/content");

        assertThat(content.at("/0").has("cache_control")).isFalse();
        assertThat(content.at("/1/cache_control/type").asText()).isEqualTo("ephemeral");
    }

    @Test
    void mediaInOneOfSeveralMessagesStaysWhereItWas() throws Exception {
        LLMRequest request =
                LLMRequest.builder()
                        .addUserMessage("first")
                        .addAssistantMessage("ok")
                        .addMessage(Message.user("look", List.of(png())))
                        .build();

        JsonNode messages = body(request).get("messages");

        assertThat(messages.at("/0/content").isTextual()).isTrue();
        assertThat(messages.at("/2/content/0/type").asText()).isEqualTo("image");
    }

    @Test
    void refusesSoundAndVideoBeforeAnyCall() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            AnthropicProvider provider =
                    new AnthropicProvider(
                            LLMConfig.builder()
                                    .apiKey("test-key")
                                    .baseUrl(server.url("/").toString())
                                    .defaultModel(MODEL)
                                    .build());
            Media clip = Media.of(MediaKind.VIDEO, TinyMedia.mp4(), "a.mp4", POLICY);

            assertThatThrownBy(() -> provider.chat(ask("watch", clip)))
                    .isInstanceOf(InvalidRequestException.class)
                    .hasMessageContaining("does not accept video")
                    .hasMessageContaining("image, document");
            assertThatThrownBy(() -> provider.chatStream(ask("watch", clip)).toList())
                    .isInstanceOf(InvalidRequestException.class);
            assertThat(server.getRequestCount()).isZero();
        }
    }
}
