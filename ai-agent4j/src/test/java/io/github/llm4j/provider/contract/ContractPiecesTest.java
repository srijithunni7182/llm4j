package io.github.llm4j.provider.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.exception.AuthenticationException;
import io.github.llm4j.exception.ContentBlockedException;
import io.github.llm4j.exception.InvalidRequestException;
import io.github.llm4j.exception.ProviderException;
import io.github.llm4j.exception.RateLimitException;
import io.github.llm4j.exception.ServiceUnavailableException;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.LLMResponse.FinishReason;
import io.github.llm4j.provider.LLMProvider;
import io.github.llm4j.provider.Providers;
import io.github.llm4j.provider.ThinkTags;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/** Verification plan C13 (default streaming), C15 (finish reasons), and the shared helpers. */
class ContractPiecesTest {

    @ParameterizedTest
    @CsvSource({
            "stop,STOP", "end_turn,STOP", "stop_sequence,STOP", "STOP,STOP", "pause_turn,STOP", "FINISH_REASON_STOP,STOP",
            "length,LENGTH", "max_tokens,LENGTH", "MAX_TOKENS,LENGTH", "model_context_window_exceeded,LENGTH",
            "content_filter,CONTENT_FILTER", "refusal,CONTENT_FILTER", "SAFETY,CONTENT_FILTER", "RECITATION,CONTENT_FILTER",
            "BLOCKLIST,CONTENT_FILTER", "PROHIBITED_CONTENT,CONTENT_FILTER", "SPII,CONTENT_FILTER",
            "tool_calls,TOOL_CALLS", "tool_use,TOOL_CALLS", "function_call,TOOL_CALLS",
            "error,ERROR", "MALFORMED_FUNCTION_CALL,ERROR",
            "something_new,UNKNOWN"
    })
    void c15_providerFinishReasonsReadTheSame(String raw, FinishReason expected) {
        assertThat(FinishReason.fromValue(raw)).isEqualTo(expected);
        assertThat(FinishReason.fromValue(null)).isEqualTo(FinishReason.UNKNOWN);
    }

    @Test
    void c13_aProviderWithoutNativeStreamingStillStreamsTheSameWay() {
        LLMProvider minimal = new LLMProvider() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                return LLMResponse.builder().content("whole answer").model("m").tokenUsage(4, 2, 6)
                        .finishReason("stop").addMetadata(Providers.FINISH_REASON_RAW, "stop").build();
            }

            @Override
            public String getProviderName() {
                return "minimal";
            }

            @Override
            public void validate() { }
        };
        List<LLMResponse> chunks = minimal.chatStream(LLMRequest.builder().addUserMessage("hi").build()).toList();
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).getContent()).isEqualTo("whole answer");
        assertThat(chunks.get(1).getContent()).isEmpty();
        assertThat(chunks.get(1).getFinishReason()).isEqualTo(FinishReason.STOP);
        assertThat(chunks.get(1).getTokenUsage().getTotalTokens()).isEqualTo(6);
        assertThat(chunks.get(1).getMetadata()).containsEntry(Providers.FINISH_REASON_RAW, "stop");
    }

    @Test
    void typedFailuresAreKeptAndOthersWrapped() {
        RuntimeException[] typed = {
                new AuthenticationException("a"), new InvalidRequestException("b"), new RateLimitException("c"),
                new ServiceUnavailableException("p", "d"), new ContentBlockedException("p", "e"), new ProviderException("p", "f")};
        for (RuntimeException e : typed) assertThat(Providers.typed("p", "wrapped", e)).isSameAs(e);
        RuntimeException wrapped = Providers.typed("p", "Failed to parse", new IOException("bad json"));
        assertThat(wrapped).isExactlyInstanceOf(ProviderException.class).hasMessageContaining("Failed to parse")
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void finalChunksCarryUsageOnlyWhenReported() {
        assertThat(Providers.finalChunk("stop", null, null, "m").getTokenUsage()).isNull();
        LLMResponse half = Providers.finalChunk(null, 5, null, "m");
        assertThat(half.getTokenUsage().getPromptTokens()).isEqualTo(5);
        assertThat(half.getFinishReason()).isEqualTo(FinishReason.UNKNOWN);
        assertThat(half.getMetadata()).doesNotContainKey(Providers.FINISH_REASON_RAW);
    }

    @Test
    void leadingThinkBlocksAreRemoved() {
        assertThat(ThinkTags.strip("<think>\nplan\n</think>\n\nAnswer")).isEqualTo("Answer");
        assertThat(ThinkTags.strip("  <think>x</think>Answer <think>kept</think>")).isEqualTo("Answer <think>kept</think>");
        assertThat(ThinkTags.strip("Plain answer")).isEqualTo("Plain answer");
        assertThat(ThinkTags.strip("<think>never closes")).isEqualTo("<think>never closes");
        assertThat(ThinkTags.strip(null)).isNull();
    }

    @Test
    void streamedThinkBlocksAreRemovedAcrossPieces() {
        assertThat(filter("<th", "ink>plan", "ning</thi", "nk>", "\n\nHel", "lo")).isEqualTo("Hello");
        assertThat(filter("<", "b>bold</b>")).isEqualTo("<b>bold</b>");
        assertThat(filter("Hi ", "<think>not leading</think>")).isEqualTo("Hi <think>not leading</think>");
        assertThat(filter("<thi")).isEqualTo("<thi"); // held back, then flushed at the end
        assertThat(filter("", null, "ok")).isEqualTo("ok");
    }

    private static String filter(String... pieces) {
        ThinkTags.StreamFilter f = new ThinkTags.StreamFilter();
        StringBuilder sb = new StringBuilder();
        for (String p : pieces) sb.append(f.accept(p));
        return sb.append(f.flush()).toString();
    }

    @Test
    void metadataStaysAMap() {
        assertThat(LLMResponse.builder().addMetadata("k", null).build().getMetadata()).isInstanceOf(Map.class);
    }
}
