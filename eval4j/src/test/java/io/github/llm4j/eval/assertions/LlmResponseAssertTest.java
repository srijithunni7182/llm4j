package io.github.llm4j.eval.assertions;

import static io.github.llm4j.eval.assertions.LlmResponseAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.model.LLMResponse;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class LlmResponseAssertTest {

    @Test
    void hasContentContaining_passesOnSubstringMatch() {
        LLMResponse response = LLMResponse.builder().content("The answer is 36.").build();
        assertThat(response).hasContentContaining("36");
    }

    @Test
    void hasContentContaining_failsWhenSubstringMissing() {
        LLMResponse response = LLMResponse.builder().content("The answer is 36.").build();
        assertThatThrownBy(() -> assertThat(response).hasContentContaining("42"))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void hasContentMatching_passesOnRegexMatch() {
        LLMResponse response = LLMResponse.builder().content("36").build();
        assertThat(response).hasContentMatching(Pattern.compile("\\d+"));
    }

    @Test
    void hasFinishReason_passesOnMatchingReason() {
        LLMResponse response =
                LLMResponse.builder().content("x").finishReason(LLMResponse.FinishReason.STOP).build();
        assertThat(response).hasFinishReason(LLMResponse.FinishReason.STOP);
    }

    @Test
    void hasFinishReason_failsOnMismatchedReason() {
        LLMResponse response =
                LLMResponse.builder().content("x").finishReason(LLMResponse.FinishReason.LENGTH).build();
        assertThatThrownBy(
                        () -> assertThat(response).hasFinishReason(LLMResponse.FinishReason.STOP))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void usesFewerTokensThan_passesWhenUnderLimit() {
        LLMResponse response =
                LLMResponse.builder().content("x").tokenUsage(10, 5, 15).build();
        assertThat(response).usesFewerTokensThan(20);
    }

    @Test
    void usesFewerTokensThan_failsWhenAtOrOverLimit() {
        LLMResponse response =
                LLMResponse.builder().content("x").tokenUsage(10, 5, 15).build();
        assertThatThrownBy(() -> assertThat(response).usesFewerTokensThan(15))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void usesFewerTokensThan_failsWhenNoTokenUsageReported() {
        LLMResponse response = LLMResponse.builder().content("x").build();
        assertThatThrownBy(() -> assertThat(response).usesFewerTokensThan(100))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void hasValidJson_passesForWellFormedJson() {
        LLMResponse response = LLMResponse.builder().content("{\"answer\":36}").build();
        assertThat(response).hasValidJson(java.util.Map.class);
    }

    @Test
    void hasValidJson_failsForMalformedJson() {
        LLMResponse response = LLMResponse.builder().content("not json").build();
        assertThatThrownBy(() -> assertThat(response).hasValidJson(java.util.Map.class))
                .isInstanceOf(AssertionError.class);
    }
}
