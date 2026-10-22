package io.github.llm4j.provider.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.provider.openai.ResponsesDialect;
import org.junit.jupiter.api.Test;

/**
 * The contract for Perplexity's Agent API: a creator/model slug or a preset, plain model access
 * unless grounding is asked for.
 */
class PerplexityAgentContractTest extends ResponsesContract {

    @Override
    protected ResponsesDialect dialect() {
        return ResponsesDialect.PERPLEXITY;
    }

    @Override
    protected String model() {
        return "openai/gpt-4.1";
    }

    @Test
    void thePathIsTheAgentEndpointAndNoToolIsSentUnlessAsked() throws Exception {
        assertThat(dialect().path()).isEqualTo("/agent");
        server.enqueue(ok(answer("ok", 1, 1)));
        provider(model()).chat(ask("hi"));
        assertThat(sent().has("tools")).isFalse();
    }

    @Test
    void aPresetIsSentAsAPresetNotAModel() throws Exception {
        server.enqueue(ok(answer("ok", 1, 1)));
        provider("preset:medium").chat(ask("hi"));
        var sent = sent();
        assertThat(sent.path("preset").asText()).isEqualTo("medium");
        assertThat(sent.has("model")).isFalse();
    }

    @Test
    void anAnthropicModelAlwaysGetsAnOutputLimitBecausePerplexityRequiresOne() throws Exception {
        server.enqueue(ok(answer("ok", 1, 1)));
        provider("anthropic/claude-sonnet-5-5").chat(ask("hi"));
        assertThat(sent().path("max_output_tokens").asInt()).isPositive();
        server.enqueue(ok(answer("ok", 1, 1)));
        provider("anthropic/claude-sonnet-5-5")
                .chat(LLMRequest.builder().addUserMessage("hi").maxTokens(77).build());
        assertThat(sent().path("max_output_tokens").asInt()).isEqualTo(77);
        server.enqueue(ok(answer("ok", 1, 1)));
        provider("openai/gpt-4.1").chat(ask("hi"));
        assertThat(sent().has("max_output_tokens")).isFalse();
    }

    @Test
    void perplexityIsSentTheTemperatureRulesOfTheModelBehindTheSlug() throws Exception {
        server.enqueue(ok(answer("ok", 1, 1)));
        provider("openai/gpt-5.6-sol")
                .chat(LLMRequest.builder().addUserMessage("hi").temperature(0.3).build());
        assertThat(sent().has("temperature")).isFalse();
        server.enqueue(ok(answer("ok", 1, 1)));
        provider("openai/gpt-4.1")
                .chat(LLMRequest.builder().addUserMessage("hi").temperature(0.3).build());
        assertThat(sent().path("temperature").asDouble()).isEqualTo(0.3);
    }
}
