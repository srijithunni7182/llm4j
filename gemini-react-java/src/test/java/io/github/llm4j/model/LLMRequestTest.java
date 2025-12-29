package io.github.llm4j.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class LLMRequestTest {

    @Test
    void testBasicRequest() {
        LLMRequest request = LLMRequest.builder()
                .addUserMessage("Hello")
                .model("gpt-4")
                .build();

        assertThat(request.getMessages()).hasSize(1);
        assertThat(request.getMessages().get(0).getContent()).isEqualTo("Hello");
        assertThat(request.getModel()).isEqualTo("gpt-4");
    }

    @Test
    void testRequestWithAllParameters() {
        LLMRequest request = LLMRequest.builder()
                .addSystemMessage("You are helpful")
                .addUserMessage("Hello")
                .model("gpt-4")
                .temperature(0.7)
                .maxTokens(100)
                .topP(0.9)
                .addParameter("customParam", "value")
                .build();

        assertThat(request.getMessages()).hasSize(2);
        assertThat(request.getTemperature()).isEqualTo(0.7);
        assertThat(request.getMaxTokens()).isEqualTo(100);
        assertThat(request.getTopP()).isEqualTo(0.9);
        assertThat(request.getAdditionalParameters()).containsEntry("customParam", "value");
    }

    @Test
    void testEmptyMessagesThrows() {
        assertThatThrownBy(() -> LLMRequest.builder().model("gpt-4").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("messages");
    }

    @Test
    void testInvalidTemperatureThrows() {
        assertThatThrownBy(() -> LLMRequest.builder()
                .addUserMessage("test")
                .temperature(-0.1)
                .build()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("temperature");

        assertThatThrownBy(() -> LLMRequest.builder()
                .addUserMessage("test")
                .temperature(2.1)
                .build()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("temperature");
    }

    @Test
    void testInvalidMaxTokensThrows() {
        assertThatThrownBy(() -> LLMRequest.builder()
                .addUserMessage("test")
                .maxTokens(-1)
                .build()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxTokens");
    }

    @Test
    void testInvalidTopPThrows() {
        assertThatThrownBy(() -> LLMRequest.builder()
                .addUserMessage("test")
                .topP(-0.1)
                .build()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topP");

        assertThatThrownBy(() -> LLMRequest.builder()
                .addUserMessage("test")
                .topP(1.1)
                .build()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topP");
    }

    @Test
    void testImmutability() {
        LLMRequest request = LLMRequest.builder()
                .addUserMessage("Hello")
                .build();

        assertThatThrownBy(() -> request.getMessages().add(Message.user("Another")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
