package io.github.llm4j.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class LLMResponseTest {

    @Test
    void testBasicResponse() {
        LLMResponse response = LLMResponse.builder()
                .content("Hello, World!")
                .model("gpt-4")
                .build();

        assertThat(response.getContent()).isEqualTo("Hello, World!");
        assertThat(response.getModel()).isEqualTo("gpt-4");
    }

    @Test
    void testResponseWithTokenUsage() {
        LLMResponse.TokenUsage tokenUsage = new LLMResponse.TokenUsage(10, 20, 30);

        LLMResponse response = LLMResponse.builder()
                .content("Response")
                .tokenUsage(tokenUsage)
                .build();

        assertThat(response.getTokenUsage()).isNotNull();
        assertThat(response.getTokenUsage().getPromptTokens()).isEqualTo(10);
        assertThat(response.getTokenUsage().getCompletionTokens()).isEqualTo(20);
        assertThat(response.getTokenUsage().getTotalTokens()).isEqualTo(30);
    }

    @Test
    void testResponseWithFinishReason() {
        LLMResponse response = LLMResponse.builder()
                .content("Response")
                .finishReason(LLMResponse.FinishReason.STOP)
                .build();

        assertThat(response.getFinishReason()).isEqualTo(LLMResponse.FinishReason.STOP);
    }

    @Test
    void testFinishReasonFromValue() {
        assertThat(LLMResponse.FinishReason.fromValue("stop"))
                .isEqualTo(LLMResponse.FinishReason.STOP);
        assertThat(LLMResponse.FinishReason.fromValue("length"))
                .isEqualTo(LLMResponse.FinishReason.LENGTH);
        assertThat(LLMResponse.FinishReason.fromValue("UNKNOWN_VALUE"))
                .isEqualTo(LLMResponse.FinishReason.UNKNOWN);
        assertThat(LLMResponse.FinishReason.fromValue(null))
                .isEqualTo(LLMResponse.FinishReason.UNKNOWN);
    }

    @Test
    void testResponseWithMetadata() {
        LLMResponse response = LLMResponse.builder()
                .content("Response")
                .addMetadata("key1", "value1")
                .addMetadata("key2", 123)
                .build();

        assertThat(response.getMetadata()).containsEntry("key1", "value1");
        assertThat(response.getMetadata()).containsEntry("key2", 123);
    }

    @Test
    void testTokenUsageEquality() {
        LLMResponse.TokenUsage usage1 = new LLMResponse.TokenUsage(10, 20, 30);
        LLMResponse.TokenUsage usage2 = new LLMResponse.TokenUsage(10, 20, 30);
        LLMResponse.TokenUsage usage3 = new LLMResponse.TokenUsage(10, 20, 31);

        assertThat(usage1).isEqualTo(usage2);
        assertThat(usage1).isNotEqualTo(usage3);
        assertThat(usage1.hashCode()).isEqualTo(usage2.hashCode());
    }
}
