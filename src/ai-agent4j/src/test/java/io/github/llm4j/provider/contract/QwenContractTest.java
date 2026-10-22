package io.github.llm4j.provider.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.provider.openai.Dialect;
import org.junit.jupiter.api.Test;

/**
 * The contract for Qwen through DashScope's compatible mode (preview: wire formats from Alibaba's
 * documentation, not recordings).
 */
class QwenContractTest extends ChatCompletionsContract {

    @Override
    protected Dialect dialect() {
        return Dialect.QWEN;
    }

    @Override
    protected String model() {
        return "qwen-turbo";
    }

    @Test
    void hybridModelsGetAnExplicitThinkingSwitch() throws Exception {
        server.enqueue(ok(answer("ok", 5, 1)));
        provider("qwen3-max").chat(ask("hi"));
        assertThat(sent().path("enable_thinking").asBoolean(true)).isFalse();

        server.enqueue(stream(java.util.List.of("a"), 1, 1));
        provider("qwen3-max")
                .chatStream(LLMRequest.builder().addUserMessage("hi").reasoning(true).build())
                .toList();
        assertThat(sent().path("enable_thinking").asBoolean(false)).isTrue();
    }

    @Test
    void aModelWithoutASwitchIsLeftAlone() throws Exception {
        server.enqueue(ok(answer("ok", 5, 1)));
        provider("qwq-plus").chat(ask("hi"));
        assertThat(sent().has("enable_thinking")).isFalse();
    }
}
