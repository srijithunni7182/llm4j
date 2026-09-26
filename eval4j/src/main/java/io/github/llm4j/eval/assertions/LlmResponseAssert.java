package io.github.llm4j.eval.assertions;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.model.LLMResponse;
import java.util.regex.Pattern;
import org.assertj.core.api.AbstractObjectAssert;

/**
 * AssertJ custom assertion for a raw {@link LLMResponse} — a single LLM call that isn't behind a
 * {@code ReActAgent}. Obtain one via {@link LlmResponseAssertions#assertThat(LLMResponse)}.
 */
public class LlmResponseAssert extends AbstractObjectAssert<LlmResponseAssert, LLMResponse> {

    public LlmResponseAssert(LLMResponse actual) {
        super(actual, LlmResponseAssert.class);
    }

    public LlmResponseAssert hasContentContaining(String expectedSubstring) {
        isNotNull();
        assertThat(actual.getContent())
                .as("content of LLM response")
                .containsIgnoringCase(expectedSubstring);
        return this;
    }

    public LlmResponseAssert hasContentMatching(Pattern pattern) {
        isNotNull();
        String content = actual.getContent();
        if (content == null || !pattern.matcher(content).find()) {
            failWithMessage(
                    "Expected response content to match pattern <%s> but was <%s>", pattern, content);
        }
        return this;
    }

    public LlmResponseAssert hasFinishReason(LLMResponse.FinishReason expected) {
        isNotNull();
        if (actual.getFinishReason() != expected) {
            failWithMessage(
                    "Expected finish reason <%s> but was <%s>", expected, actual.getFinishReason());
        }
        return this;
    }

    public LlmResponseAssert usesFewerTokensThan(int maxTotalTokens) {
        isNotNull();
        if (actual.getTokenUsage() == null) {
            failWithMessage(
                    "Expected response to report token usage under <%s> but no TokenUsage was"
                            + " present",
                    maxTotalTokens);
            return this;
        }
        int actualTokens = actual.getTokenUsage().getTotalTokens();
        if (actualTokens >= maxTotalTokens) {
            failWithMessage(
                    "Expected response to use fewer than <%s> total tokens but used <%s>",
                    maxTotalTokens, actualTokens);
        }
        return this;
    }

    public LlmResponseAssert hasValidJson(Class<?> shape) {
        isNotNull();
        try {
            new ObjectMapper().readValue(actual.getContent(), shape);
        } catch (Exception e) {
            failWithMessage(
                    "Expected response content to be valid JSON matching <%s> but parsing failed: %s",
                    shape.getSimpleName(), e.getMessage());
        }
        return this;
    }
}
