package io.github.llm4j.provider.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.provider.openai.Dialect;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The contract for OpenAI's chat completions: reasoning models drop sampling settings, and the
 * token limit is {@code max_completion_tokens}.
 */
class OpenAiContractTest extends ChatCompletionsContract {

    @Override
    protected Dialect dialect() {
        return Dialect.OPENAI;
    }

    @Override
    protected String model() {
        return "gpt-4.1"; // a classic model: accepts sampling settings, so C3 sees them
    }

    @Test
    void aReasoningModelIsNotSentSamplingSettingsItWouldReject() throws Exception {
        server.enqueue(ok(answer("ok", 5, 1)));
        provider("gpt-5-mini")
                .chat(
                        LLMRequest.builder()
                                .addUserMessage("Hi")
                                .maxTokens(50)
                                .temperature(0.2)
                                .topP(0.9)
                                .stopSequences(List.of("END"))
                                .build());
        var sent = sent();
        assertThat(sent.has("temperature")).isFalse();
        assertThat(sent.has("top_p")).isFalse();
        assertThat(sent.has("stop")).isFalse();
        assertThat(sent.path("max_completion_tokens").asInt()).isEqualTo(50);
        assertThat(sent.has("max_tokens")).isFalse();
    }

    @Test
    void anUnknownFutureModelDegradesToTheSafeDefaults() throws Exception {
        server.enqueue(ok(answer("ok", 5, 1)));
        provider("o9-ultra-2031-01-01")
                .chat(
                        LLMRequest.builder()
                                .addUserMessage("Hi")
                                .temperature(0.2)
                                .maxTokens(10)
                                .build());
        var sent = sent();
        assertThat(sent.has("temperature")).isFalse();
        assertThat(sent.path("max_completion_tokens").asInt()).isEqualTo(10);
    }

    @Test
    void reasoningEffortGoesThroughAdditionalParameters() throws Exception {
        server.enqueue(ok(answer("ok", 5, 1)));
        provider("gpt-5-mini")
                .chat(
                        LLMRequest.builder()
                                .addUserMessage("Hi")
                                .addParameter("reasoning_effort", "low")
                                .build());
        assertThat(sent().path("reasoning_effort").asText()).isEqualTo("low");
    }

    @Test
    void cachedPromptTokensAreReportedInMetadata() {
        server.enqueue(
                ok(
                        """
                {"choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":2006,"completion_tokens":300,"total_tokens":2306,
                  "prompt_tokens_details":{"cached_tokens":1920},"completion_tokens_details":{"reasoning_tokens":256}}}"""));
        var r = provider("gpt-5-mini").chat(ask("hi"));
        assertThat(r.getMetadata())
                .containsEntry("usage.cached_tokens", 1920)
                .containsEntry("usage.reasoning_tokens", 256);
    }
}
