package io.github.llm4j.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.exception.InvalidRequestException;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.Media;
import io.github.llm4j.model.MediaKind;
import io.github.llm4j.model.MediaPolicy;
import io.github.llm4j.model.Message;
import io.github.llm4j.model.TinyMedia;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Media on chat completions and on the Responses schema (docs/specs/31-multimodal-input R11).
 * Shapes confirmed against the real API on 2026-11-06 (OpenAI: image, PDF as a {@code file} part,
 * audio on {@code gpt-audio} models, and the Responses {@code input_image} and {@code input_file};
 * xAI: image).
 */
class MediaBodyTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final MediaPolicy POLICY = MediaPolicy.defaults();

    private static Media media(MediaKind kind, byte[] bytes, String name) {
        return Media.of(kind, bytes, name, POLICY);
    }

    private static LLMRequest ask(String text, Media... media) {
        return LLMRequest.builder().addMessage(Message.user(text, List.of(media))).build();
    }

    private static JsonNode chat(LLMRequest request) throws Exception {
        return JSON.readTree(ChatRequestWriter.write(Dialect.OPENAI, request, "gpt-5-mini", false));
    }

    private static JsonNode responses(LLMRequest request) throws Exception {
        return JSON.readTree(
                ResponsesRequestWriter.write(
                        ResponsesDialect.OPENAI, request, "gpt-5-mini", false));
    }

    // ── chat completions ───────────────────────────────────────────────────────────────────

    @Test
    void chatImageIsADataUriInAnImageUrlPart() throws Exception {
        JsonNode content =
                chat(ask("What colour?", media(MediaKind.IMAGE, TinyMedia.png(), "a.png")))
                        .at("/messages/0/content");

        assertThat(content.at("/0/type").asText()).isEqualTo("image_url");
        assertThat(content.at("/0/image_url/url").asText())
                .isEqualTo("data:image/png;base64," + TinyMedia.base64(TinyMedia.png()));
        assertThat(content.at("/1/type").asText()).isEqualTo("text");
        assertThat(content.at("/1/text").asText()).isEqualTo("What colour?");
    }

    @Test
    void chatPdfIsAFilePartWithItsNameAndADataUri() throws Exception {
        JsonNode part =
                chat(ask("Total?", media(MediaKind.DOCUMENT, TinyMedia.pdf(), "total.pdf")))
                        .at("/messages/0/content/0");

        assertThat(part.get("type").asText()).isEqualTo("file");
        assertThat(part.at("/file/filename").asText()).isEqualTo("total.pdf");
        assertThat(part.at("/file/file_data").asText())
                .isEqualTo("data:application/pdf;base64," + TinyMedia.base64(TinyMedia.pdf()));
    }

    @Test
    void chatWavAndMp3AreInputAudioWithTheirFormat() throws Exception {
        JsonNode wav =
                chat(ask("?", media(MediaKind.AUDIO, TinyMedia.wav(), "a.wav")))
                        .at("/messages/0/content/0");
        JsonNode mp3 =
                chat(ask("?", media(MediaKind.AUDIO, TinyMedia.mp3(), "a.mp3")))
                        .at("/messages/0/content/0");

        assertThat(wav.get("type").asText()).isEqualTo("input_audio");
        assertThat(wav.at("/input_audio/format").asText()).isEqualTo("wav");
        assertThat(wav.at("/input_audio/data").asText())
                .isEqualTo(TinyMedia.base64(TinyMedia.wav()));
        assertThat(mp3.at("/input_audio/format").asText()).isEqualTo("mp3");
    }

    @Test
    void chatRefusesAnAudioFormatTheApiDoesNotTake() {
        LLMRequest request = ask("?", media(MediaKind.AUDIO, TinyMedia.m4a(), "a.m4a"));

        assertThatThrownBy(() -> chat(request))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("audio/mp4")
                .hasMessageContaining("wav")
                .hasMessageContaining("mp3");
    }

    @Test
    void chatRefusesVideoItCannotWrite() {
        LLMRequest request = ask("?", media(MediaKind.VIDEO, TinyMedia.mp4(), "a.mp4"));

        assertThatThrownBy(() -> chat(request)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void chatEmptyTextIsLeftOutOfTheParts() throws Exception {
        JsonNode content =
                chat(ask("", media(MediaKind.IMAGE, TinyMedia.png(), "a.png")))
                        .at("/messages/0/content");

        assertThat(content).hasSize(1);
    }

    @Test
    void chatTextOnlyStaysAString() throws Exception {
        assertThat(chat(ask("hi")).at("/messages/0/content").isTextual()).isTrue();
    }

    @Test
    void anXaiRequestUsesTheSameImagePart() throws Exception {
        JsonNode content =
                JSON.readTree(
                                ChatRequestWriter.write(
                                        Dialect.XAI,
                                        ask("?", media(MediaKind.IMAGE, TinyMedia.jpeg(), "a.jpg")),
                                        "grok-4",
                                        false))
                        .at("/messages/0/content/0");

        assertThat(content.at("/image_url/url").asText()).startsWith("data:image/jpeg;base64,");
    }

    // ── the Responses schema ───────────────────────────────────────────────────────────────

    @Test
    void responsesImageIsAnInputImageWithADataUri() throws Exception {
        JsonNode content =
                responses(ask("What colour?", media(MediaKind.IMAGE, TinyMedia.png(), "a.png")))
                        .at("/input/0/content");

        assertThat(content.at("/0/type").asText()).isEqualTo("input_image");
        assertThat(content.at("/0/image_url").asText())
                .isEqualTo("data:image/png;base64," + TinyMedia.base64(TinyMedia.png()));
        assertThat(content.at("/1/type").asText()).isEqualTo("input_text");
        assertThat(content.at("/1/text").asText()).isEqualTo("What colour?");
    }

    @Test
    void responsesPdfIsAnInputFile() throws Exception {
        JsonNode part =
                responses(ask("Total?", media(MediaKind.DOCUMENT, TinyMedia.pdf(), "total.pdf")))
                        .at("/input/0/content/0");

        assertThat(part.get("type").asText()).isEqualTo("input_file");
        assertThat(part.get("filename").asText()).isEqualTo("total.pdf");
        assertThat(part.get("file_data").asText())
                .isEqualTo("data:application/pdf;base64," + TinyMedia.base64(TinyMedia.pdf()));
    }

    @Test
    void responsesRefusesSoundBecauseOnlyChatCompletionsTakesIt() {
        LLMRequest request = ask("?", media(MediaKind.AUDIO, TinyMedia.wav(), "a.wav"));

        assertThatThrownBy(() -> responses(request)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void responsesTextOnlyStaysAString() throws Exception {
        assertThat(responses(ask("hi")).at("/input/0/content").isTextual()).isTrue();
    }
}
