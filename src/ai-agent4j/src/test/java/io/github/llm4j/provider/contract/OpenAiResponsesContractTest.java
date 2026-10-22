package io.github.llm4j.provider.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.provider.openai.ResponsesDialect;
import org.junit.jupiter.api.Test;

/** The contract for OpenAI's own Responses API ({@code api: responses}). */
class OpenAiResponsesContractTest extends ResponsesContract {

    @Override
    protected ResponsesDialect dialect() {
        return ResponsesDialect.OPENAI;
    }

    @Override
    protected String model() {
        return "gpt-4.1";
    }

    @Test
    void aReasoningModelIsNotSentSamplingSettingsAndTakesReasoningConfiguration() throws Exception {
        server.enqueue(ok(answer("ok", 5, 1)));
        provider("gpt-5-mini")
                .chat(
                        LLMRequest.builder()
                                .addUserMessage("Hi")
                                .temperature(0.2)
                                .topP(0.9)
                                .maxTokens(50)
                                .addParameter("reasoning", java.util.Map.of("effort", "low"))
                                .build());
        var sent = sent();
        assertThat(sent.has("temperature")).isFalse();
        assertThat(sent.has("top_p")).isFalse();
        assertThat(sent.path("max_output_tokens").asInt()).isEqualTo(50);
        assertThat(sent.path("reasoning").path("effort").asText()).isEqualTo("low");
    }

    @Test
    void askingForReasoningOnAStreamRequestsSummaries() throws Exception {
        server.enqueue(stream(java.util.List.of("a"), 1, 1));
        provider("gpt-5-mini")
                .chatStream(LLMRequest.builder().addUserMessage("Hi").reasoning(true).build())
                .toList();
        assertThat(sent().path("reasoning").path("summary").asText()).isEqualTo("auto");
    }
}
