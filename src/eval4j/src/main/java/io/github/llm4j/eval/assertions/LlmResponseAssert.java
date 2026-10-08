package io.github.llm4j.eval.assertions;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.eval.export.EvalChecks;
import io.github.llm4j.eval.export.MetricRef;
import io.github.llm4j.model.LLMResponse;
import java.util.regex.Pattern;
import org.assertj.core.api.AbstractObjectAssert;

/**
 * AssertJ custom assertion for a raw {@link LLMResponse} — a single LLM call that isn't behind a
 * {@code ReActAgent}. Obtain one via {@link LlmResponseAssertions#assertThat(LLMResponse)}.
 */
public class LlmResponseAssert extends AbstractObjectAssert<LlmResponseAssert, LLMResponse> {

    private static final MetricRef M_TOKENS =
            MetricRef.measured(
                    "token-budget", "Token budget", "prompts", "answers", "efficiency", "tokens");

    private static final MetricRef M_HASCONTENTCONTAINING =
            MetricRef.assertion(
                    "content-contains", "Content contains", "prompts", "answers", "correctness");
    private static final MetricRef M_HASCONTENTMATCHING =
            MetricRef.assertion(
                    "content-matches", "Content matches", "prompts", "answers", "correctness");
    private static final MetricRef M_HASFINISHREASON =
            MetricRef.assertion(
                    "finish-reason", "Finish reason", "prompts", "answers", "reliability");
    private static final MetricRef M_HASVALIDJSON =
            MetricRef.assertion(
                    "valid-json", "Valid JSON output", "prompts", "answers", "reliability");

    public LlmResponseAssert(LLMResponse actual) {
        super(actual, LlmResponseAssert.class);
    }

    public LlmResponseAssert hasContentContaining(String expectedSubstring) {
        EvalChecks.check(
                M_HASCONTENTCONTAINING,
                () -> {
                    isNotNull();
                    assertThat(actual.getContent())
                            .as("content of LLM response")
                            .containsIgnoringCase(expectedSubstring);
                });
        return this;
    }

    public LlmResponseAssert hasContentMatching(Pattern pattern) {
        EvalChecks.check(
                M_HASCONTENTMATCHING,
                () -> {
                    isNotNull();
                    String content = actual.getContent();
                    if (content == null || !pattern.matcher(content).find()) {
                        failWithMessage(
                                "Expected response content to match pattern <%s> but was <%s>",
                                pattern, content);
                    }
                });
        return this;
    }

    public LlmResponseAssert hasFinishReason(LLMResponse.FinishReason expected) {
        EvalChecks.check(
                M_HASFINISHREASON,
                () -> {
                    isNotNull();
                    if (actual.getFinishReason() != expected) {
                        failWithMessage(
                                "Expected finish reason <%s> but was <%s>",
                                expected, actual.getFinishReason());
                    }
                });
        return this;
    }

    public LlmResponseAssert usesFewerTokensThan(int maxTotalTokens) {
        EvalChecks.checkMeasured(
                M_TOKENS,
                actual == null
                        ? null
                        : (double)
                                (actual.getTokenUsage() == null
                                        ? 0
                                        : actual.getTokenUsage().getTotalTokens()),
                "tokens",
                (double) maxTotalTokens,
                () -> {
                    isNotNull();
                    if (actual.getTokenUsage() == null) {
                        failWithMessage(
                                "Expected response to report token usage under <%s> but no TokenUsage was present",
                                maxTotalTokens);
                        return;
                    }
                    int actualTokens = actual.getTokenUsage().getTotalTokens();
                    if (actualTokens >= maxTotalTokens) {
                        failWithMessage(
                                "Expected response to use fewer than <%s> total tokens but used <%s>",
                                maxTotalTokens, actualTokens);
                    }
                });
        return this;
    }

    public LlmResponseAssert hasValidJson(Class<?> shape) {
        EvalChecks.check(
                M_HASVALIDJSON,
                () -> {
                    isNotNull();
                    try {
                        new ObjectMapper().readValue(actual.getContent(), shape);
                    } catch (Exception e) {
                        failWithMessage(
                                "Expected response content to be valid JSON matching <%s> but parsing failed: %s",
                                shape.getSimpleName(), e.getMessage());
                    }
                });
        return this;
    }
}
