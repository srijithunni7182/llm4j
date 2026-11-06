package io.github.llm4j.provider.sarvam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.exception.InvalidRequestException;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.Media;
import io.github.llm4j.model.MediaKind;
import io.github.llm4j.model.MediaPolicy;
import io.github.llm4j.model.Message;
import io.github.llm4j.model.TinyMedia;
import java.util.List;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

/**
 * A provider that cannot send media refuses it when called directly, not only through {@code
 * DefaultLLMClient} (docs/specs/31-multimodal-input R3): media is never silently dropped.
 */
class MediaRefusalTest {

    @Test
    void sarvamRefusesMediaOnEveryPathAndSendsNothing() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            SarvamChatProvider provider =
                    new SarvamChatProvider(
                            LLMConfig.builder()
                                    .apiKey("test-key")
                                    .baseUrl(server.url("").toString())
                                    .defaultModel("sarvam-m")
                                    .build());
            LLMRequest request =
                    LLMRequest.builder()
                            .addMessage(
                                    Message.user(
                                            "look",
                                            List.of(
                                                    Media.of(
                                                            MediaKind.IMAGE,
                                                            TinyMedia.png(),
                                                            "a.png",
                                                            MediaPolicy.defaults()))))
                            .build();

            assertThatThrownBy(() -> provider.chat(request))
                    .isInstanceOf(InvalidRequestException.class)
                    .hasMessageContaining("sarvam");
            assertThatThrownBy(() -> provider.chatStream(request).toList())
                    .isInstanceOf(InvalidRequestException.class);
            assertThat(server.getRequestCount()).isZero();
        }
    }
}
