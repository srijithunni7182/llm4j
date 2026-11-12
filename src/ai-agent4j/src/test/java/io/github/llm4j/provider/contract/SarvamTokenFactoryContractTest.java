package io.github.llm4j.provider.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.provider.openai.Dialect;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The contract for Sarvam's V2 chat completions, which serves {@code sarvam-105b} and the Token
 * Factory's open-weight models (preview: wire formats from Sarvam's documentation, not recordings).
 * Each model family has its own way of switching thinking off.
 */
class SarvamTokenFactoryContractTest extends ChatCompletionsContract {

    @Override
    protected Dialect dialect() {
        return Dialect.SARVAM;
    }

    @Override
    protected String model() {
        return "glm5.3"; // always thinks: no switch, so the request carries only what was asked
    }

    @Test
    void callsTheV2PathWithTheSubscriptionKeyHeader() throws Exception {
        server.enqueue(ok(answer("ok", 5, 1)));
        provider("glm5.3").chat(ask("hi"));
        var recorded = server.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/v1/v2/chat/completions");
        assertThat(recorded.getHeader("api-subscription-key")).isNotBlank();
        assertThat(recorded.getHeader("Authorization")).isNull();
    }

    @Test
    void switchableModelsAreSentEffortNoneUnlessAStreamAsksToSeeReasoning() throws Exception {
        for (String model : List.of("sarvam-105b", "deepseekv4.1-flash-1017", "qwen38-27b")) {
            server.enqueue(ok(answer("ok", 5, 1)));
            provider(model).chat(ask("hi"));
            assertThat(sent().path("reasoning_effort").asText()).as(model).isEqualTo("none");

            server.enqueue(stream(List.of("a"), 1, 1));
            provider(model)
                    .chatStream(LLMRequest.builder().addUserMessage("hi").reasoning(true).build())
                    .toList();
            assertThat(sent().has("reasoning_effort")).as(model).isFalse();
        }
    }

    @Test
    void gemmaThinksOnlyWhenAStreamAsksToSeeReasoning() throws Exception {
        server.enqueue(ok(answer("ok", 5, 1)));
        provider("gemma4").chat(ask("hi"));
        assertThat(sent().has("reasoning_effort")).isFalse();

        server.enqueue(stream(List.of("a"), 1, 1));
        provider("gemma4")
                .chatStream(LLMRequest.builder().addUserMessage("hi").reasoning(true).build())
                .toList();
        assertThat(sent().path("reasoning_effort").asText()).isEqualTo("low");
    }

    @Test
    void anEffortTheCallerGivesWinsOverTheSwitch() throws Exception {
        server.enqueue(ok(answer("ok", 5, 1)));
        provider("sarvam-105b")
                .chat(
                        LLMRequest.builder()
                                .addUserMessage("hi")
                                .addParameter("reasoning_effort", "high")
                                .build());
        assertThat(sent().path("reasoning_effort").asText()).isEqualTo("high");
    }

    @Test
    void theConversationsVariantAndAlwaysThinkingModelsAreLeftAlone() throws Exception {
        for (String model : List.of("sarvam-105b-conversations", "kimi-k3.1-pro", "glm5.3-flash")) {
            server.enqueue(ok(answer("ok", 5, 1)));
            provider(model).chat(ask("hi"));
            assertThat(sent().has("reasoning_effort")).as(model).isFalse();
        }
    }
}
