package io.github.llm4j.provider.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.provider.openai.Dialect;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The contract for xAI's chat completions: the reasoning models refuse stop sequences, and
 * reasoning text is {@code reasoning_content}.
 */
class XaiContractTest extends ChatCompletionsContract {

    @Override
    protected Dialect dialect() {
        return Dialect.XAI;
    }

    @Override
    protected String model() {
        return "grok-3"; // a non-reasoning model: takes stop sequences, so C3 sees them
    }

    @Test
    void aReasoningModelIsNotSentStopSequencesButKeepsItsTemperature() throws Exception {
        server.enqueue(ok(answer("ok", 5, 1)));
        provider("grok-4")
                .chat(
                        LLMRequest.builder()
                                .addUserMessage("Hi")
                                .temperature(0.3)
                                .stopSequences(List.of("END"))
                                .maxTokens(20)
                                .build());
        var sent = sent();
        assertThat(sent.has("stop")).isFalse();
        assertThat(sent.path("temperature").asDouble()).isEqualTo(0.3);
        assertThat(sent.path("max_completion_tokens").asInt()).isEqualTo(20);
    }

    @Test
    void searchCallsAndCostAreNotMistakenForTheAnswer() {
        server.enqueue(
                ok(
                        """
                {"choices":[{"index":0,"message":{"role":"assistant","content":"Hi.","reasoning_content":"thinking","refusal":null},"finish_reason":"stop"}],
                 "citations":["https://example.com/a"],
                 "usage":{"prompt_tokens":3,"completion_tokens":4,"total_tokens":7,"cost_in_usd_ticks":1234,
                  "server_side_tool_usage_details":{"web_search_calls":0}}}"""));
        assertThat(provider("grok-4").chat(ask("hi")).getContent()).isEqualTo("Hi.");
    }
}
