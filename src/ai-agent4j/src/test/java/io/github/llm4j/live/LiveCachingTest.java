package io.github.llm4j.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.budget.BudgetedLLMClient;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.PromptCache;
import io.github.llm4j.provider.Providers;
import io.github.llm4j.provider.anthropic.AnthropicProvider;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Prompt caching against the real Anthropic API (docs/specs/31-multimodal-input, M7): the first
 * call writes the marked prompt to the cache and the second reads it back. Tagged {@code live};
 * needs {@code ANTHROPIC_API_KEY}.
 */
@Tag("live")
class LiveCachingTest {

    /** Well over the shortest prompt Anthropic caches, so the marker is honoured. */
    private static final String LONG_PROMPT =
            IntStream.range(0, 520)
                    .mapToObj(
                            i ->
                                    "Fact number "
                                            + i
                                            + ": the quick brown fox jumps over the lazy dog.")
                    .collect(Collectors.joining(" "));

    private static LLMResponse call(LLMClient client) {
        return client.chat(
                LLMRequest.builder()
                        .addSystemMessage(LONG_PROMPT)
                        .addUserMessage("Say OK.")
                        .maxTokens(200)
                        .promptCache(PromptCache.builder().system().build())
                        .build());
    }

    @Test
    void theSecondCallReadsWhatTheFirstWrote() {
        String key = LiveTargets.env("ANTHROPIC_API_KEY");
        assumeTrue(key != null, "ANTHROPIC_API_KEY not set");
        String model = LiveTargets.models("ANTHROPIC_TEST_MODELS", "claude-haiku-5-5").get(0);
        LLMClient client =
                BudgetedLLMClient.builder(
                                new DefaultLLMClient(
                                        new AnthropicProvider(
                                                LLMConfig.builder()
                                                        .apiKey(key)
                                                        .defaultModel(model)
                                                        .build())))
                        .budget(LiveTargets.budget("anthropic-cache"))
                        .build();

        LLMResponse first = call(client);
        LLMResponse second = call(client);

        assertThat(((Number) first.getMetadata().get(Providers.CACHE_WRITE_TOKENS)).intValue())
                .isGreaterThan(1000);
        assertThat(((Number) second.getMetadata().get(Providers.CACHED_TOKENS)).intValue())
                .isGreaterThan(1000);
    }
}
