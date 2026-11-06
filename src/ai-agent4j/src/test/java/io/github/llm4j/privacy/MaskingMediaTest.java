package io.github.llm4j.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.LLMClient;
import io.github.llm4j.exception.InvalidRequestException;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.Media;
import io.github.llm4j.model.MediaCapabilities;
import io.github.llm4j.model.MediaKind;
import io.github.llm4j.model.MediaPolicy;
import io.github.llm4j.model.Message;
import io.github.llm4j.model.TinyMedia;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * docs/specs/31-multimodal-input R13: masking works on text, so it cannot vouch for a picture, a
 * recording or a document. With masking on, media is refused unless the caller says it accepts
 * sending it unscanned.
 */
class MaskingMediaTest {

    /** Takes pictures and records every request it is given. */
    private static final class Recording implements LLMClient {
        final List<LLMRequest> seen = new ArrayList<>();

        @Override
        public LLMResponse chat(LLMRequest request) {
            seen.add(request);
            return LLMResponse.builder().content("ok").model("m").tokenUsage(1, 1, 2).build();
        }

        @Override
        public Stream<LLMResponse> chatStream(LLMRequest request) {
            return Stream.of(chat(request));
        }

        @Override
        public MediaCapabilities mediaCapabilities(String model) {
            return MediaCapabilities.of(MediaKind.IMAGE);
        }
    }

    private static LLMRequest withPhoto(String text) {
        Media photo = Media.of(MediaKind.IMAGE, TinyMedia.png(), "a.png", MediaPolicy.defaults());
        return LLMRequest.builder().addMessage(Message.user(text, List.of(photo))).build();
    }

    @Test
    void refusesMediaByDefaultAndSendsNothing() {
        Recording model = new Recording();
        MaskingLLMClient client = new MaskingLLMClient(model);

        assertThatThrownBy(() -> client.chat(withPhoto("look")))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("masking")
                .hasMessageContaining("cannot see inside")
                .hasMessageContaining("allowUnscannedMedia")
                .hasMessageContaining("media: unscanned");
        assertThat(model.seen).isEmpty();
    }

    @Test
    void refusesOnAStreamedCallToo() {
        Recording model = new Recording();

        assertThatThrownBy(() -> new MaskingLLMClient(model).chatStream(withPhoto("look")).toList())
                .isInstanceOf(InvalidRequestException.class);
        assertThat(model.seen).isEmpty();
    }

    @Test
    void theRefusalNeverContainsTheMediaOrItsName() {
        assertThatThrownBy(() -> new MaskingLLMClient(new Recording()).chat(withPhoto("look")))
                .satisfies(
                        e ->
                                assertThat(e.getMessage())
                                        .doesNotContain(TinyMedia.base64(TinyMedia.png()))
                                        .doesNotContain("a.png"));
    }

    @Test
    void sendsMediaOnceTheCallerAcceptsItUnscannedAndStillMasksTheText() {
        Recording model = new Recording();
        MaskingLLMClient client = new MaskingLLMClient(model).allowUnscannedMedia();

        client.chat(withPhoto("my email is a.b@example.com"));

        Message sent = model.seen.get(0).getMessages().get(0);
        assertThat(sent.getMedia()).hasSize(1);
        assertThat(sent.getContent()).doesNotContain("a.b@example.com").contains("[EMAIL]");
    }

    @Test
    void allowingItGivesANewClientAndLeavesTheFirstStrict() {
        Recording model = new Recording();
        MaskingLLMClient strict = new MaskingLLMClient(model);
        MaskingLLMClient loose = strict.allowUnscannedMedia();

        assertThat(loose).isNotSameAs(strict);
        assertThatThrownBy(() -> strict.chat(withPhoto("x")))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void textOnlyRequestsAreNeverAffected() {
        Recording model = new Recording();

        new MaskingLLMClient(model).chat(LLMRequest.builder().addUserMessage("hi").build());

        assertThat(model.seen).hasSize(1);
    }
}
