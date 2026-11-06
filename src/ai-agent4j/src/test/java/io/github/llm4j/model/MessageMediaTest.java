package io.github.llm4j.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * docs/specs/31-multimodal-input R7 and R9: a message can carry media next to its text, existing
 * code that only reads text is unaffected, and nothing writes the bytes out by accident.
 */
class MessageMediaTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final MediaPolicy POLICY = MediaPolicy.defaults();

    private static Media photo() {
        return Media.of(MediaKind.IMAGE, TinyMedia.png(), "receipt.png", POLICY);
    }

    @Test
    void aMessageHasNoMediaUnlessGivenSome() {
        assertThat(Message.user("hi").getMedia()).isEmpty();
        assertThat(new Message(Message.Role.USER, "hi", null).getMedia()).isEmpty();
    }

    @Test
    void carriesMediaNextToItsText() {
        Message m = Message.user("What is the total?", List.of(photo()));

        assertThat(m.getContent()).isEqualTo("What is the total?");
        assertThat(m.getMedia()).containsExactly(photo());
    }

    @Test
    void theMediaListCannotBeChangedAfterwards() {
        Message m = Message.user("x", List.of(photo()));

        assertThatThrownBy(() -> m.getMedia().add(photo()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void onlyAUserMessageCanCarryMedia() {
        for (Message.Role role :
                List.of(Message.Role.SYSTEM, Message.Role.ASSISTANT, Message.Role.TOOL)) {
            assertThatThrownBy(
                            () ->
                                    Message.builder()
                                            .role(role)
                                            .content("x")
                                            .media(List.of(photo()))
                                            .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(role.getValue())
                    .hasMessageContaining("user");
        }
    }

    @Test
    void changingTheTextKeepsTheMedia() {
        Message m = Message.user("original", List.of(photo()));

        assertThat(m.withContent("masked").getMedia()).containsExactly(photo());
        assertThat(m.toBuilder().build()).isEqualTo(m);
    }

    @Test
    void mediaIsPartOfWhatMakesTwoMessagesEqual() {
        assertThat(Message.user("x", List.of(photo()))).isNotEqualTo(Message.user("x"));
        assertThat(Message.user("x", List.of(photo())))
                .isEqualTo(Message.user("x", List.of(photo())))
                .hasSameHashCodeAs(Message.user("x", List.of(photo())));
    }

    @Test
    void printsTheMediaAsItsShortFormAndNeverTheBytes() {
        String text = Message.user("look", List.of(photo())).toString();

        assertThat(text)
                .contains("media:image/png:64B:")
                .doesNotContain(TinyMedia.base64(TinyMedia.png()));
    }

    @Test
    void writesDescriptorsToJsonAndNeverTheBytes() throws Exception {
        String json = JSON.writeValueAsString(Message.user("look", List.of(photo())));

        JsonNode media = JSON.readTree(json).get("media");
        assertThat(media).hasSize(1);
        assertThat(media.get(0).get("mediaType").asText()).isEqualTo("image/png");
        assertThat(json).doesNotContain(TinyMedia.base64(TinyMedia.png()));
    }

    @Test
    void aTextOnlyMessageWritesNoMediaField() throws Exception {
        assertThat(JSON.readTree(JSON.writeValueAsString(Message.user("hi"))).has("media"))
                .isFalse();
    }

    @Test
    void readingAMessageBackKeepsTheTextAndLeavesTheMediaForTheCallerToReload() throws Exception {
        String json = JSON.writeValueAsString(Message.user("look", List.of(photo())));

        Message back = JSON.readValue(json, Message.class);

        assertThat(back.getContent()).isEqualTo("look");
        assertThat(back.getMedia()).isEmpty();
    }
}
