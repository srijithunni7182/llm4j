package io.github.llm4j.provider.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.provider.openai.Dialect;
import org.junit.jupiter.api.Test;

/**
 * The contract for Kimi (preview: the wire formats come from Moonshot's documentation, not from
 * recordings). Newer models fix their sampling settings, and K2.6 has a thinking switch.
 */
class KimiContractTest extends ChatCompletionsContract {

    @Override
    protected Dialect dialect() {
        return Dialect.KIMI;
    }

    @Override
    protected String model() {
        return "kimi-k2-0905-preview"; // an older model: takes sampling settings, so C3 sees them
    }

    @Test
    void k3IsNotSentSettingsItFixesAndTakesReasoningEffortThroughAdditionalParameters()
            throws Exception {
        server.enqueue(ok(answer("ok", 5, 1)));
        provider("kimi-k3")
                .chat(
                        LLMRequest.builder()
                                .addUserMessage("Hi")
                                .temperature(0.2)
                                .topP(0.5)
                                .maxTokens(30)
                                .addParameter("reasoning_effort", "low")
                                .build());
        var sent = sent();
        assertThat(sent.has("temperature")).isFalse();
        assertThat(sent.has("top_p")).isFalse();
        assertThat(sent.path("max_completion_tokens").asInt()).isEqualTo(30);
        assertThat(sent.path("reasoning_effort").asText()).isEqualTo("low");
        assertThat(sent.has("thinking")).isFalse();
    }

    @Test
    void k26ThinkingIsSwitchedOffUnlessAStreamAsksToSeeReasoning() throws Exception {
        server.enqueue(ok(answer("ok", 5, 1)));
        provider("kimi-k2.6").chat(ask("hi"));
        assertThat(sent().path("thinking").path("type").asText()).isEqualTo("disabled");

        server.enqueue(stream(java.util.List.of("a"), 1, 1));
        provider("kimi-k2.6")
                .chatStream(LLMRequest.builder().addUserMessage("hi").reasoning(true).build())
                .toList();
        assertThat(sent().path("thinking").path("type").asText()).isEqualTo("enabled");
    }
}
