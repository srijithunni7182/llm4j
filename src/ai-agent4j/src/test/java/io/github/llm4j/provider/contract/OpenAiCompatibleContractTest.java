package io.github.llm4j.provider.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.provider.LLMProvider;
import io.github.llm4j.provider.openai.Dialect;
import io.github.llm4j.provider.openai.OpenAiCompatibleProvider;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The contract for the generic kind (any OpenAI-style server) with its configurable header name and
 * fixed query parameters.
 */
class OpenAiCompatibleContractTest extends ChatCompletionsContract {

    @Override
    protected Dialect dialect() {
        return Dialect.GENERIC;
    }

    @Override
    protected String model() {
        return "local-model";
    }

    @Test
    void aGatewayCanWantAnotherHeaderAndAFixedQueryParameter() throws Exception {
        Dialect gateway =
                Dialect.GENERIC
                        .withAuthHeader("api-key", "")
                        .withQueryParams(Map.of("api-version", "2025-01-01"));
        LLMProvider p =
                new OpenAiCompatibleProvider(gateway, config(url("/openai/deployments/x"), "gpt"));
        server.enqueue(ok(answer("ok", 1, 1)));
        p.chat(LLMRequest.builder().addUserMessage("hi").build());
        var r = server.takeRequest();
        assertThat(r.getPath())
                .isEqualTo("/openai/deployments/x/chat/completions?api-version=2025-01-01");
        assertThat(r.getHeader("api-key")).isEqualTo(SECRET);
        assertThat(r.getHeader("Authorization")).isNull();
    }

    @Test
    void aLeadingThinkBlockIsAlsoRemovedFromServersThatWriteReasoningIntoTheAnswer() {
        server.enqueue(ok(completion("stop", "<think>plan</think>\n\nParis.", 3, 3)));
        assertThat(provider(model()).chat(ask("capital?")).getContent()).isEqualTo("Paris.");
    }

    @Test
    void theReasoningFieldSomeServersUseIsNotTheAnswerEither() {
        server.enqueue(
                ok(
                        """
                {"choices":[{"index":0,"message":{"role":"assistant","reasoning":"hmm","content":"Paris."},"finish_reason":"stop"}]}"""));
        assertThat(provider(model()).chat(ask("capital?")).getContent()).isEqualTo("Paris.");
    }

    @Override
    protected LLMProvider provider(String model) {
        return new OpenAiCompatibleProvider(
                Dialect.GENERIC,
                LLMConfig.builder()
                        .apiKey(SECRET)
                        .baseUrl(url("/v1"))
                        .defaultModel(model)
                        .retryPolicy(config(url(""), model).getRetryPolicy())
                        .build());
    }
}
