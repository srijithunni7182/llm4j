package io.github.llm4j.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** docs/specs/31-multimodal-input R1: which stable parts of a request may be cached. */
class PromptCacheTest {

    @Test
    void asksForNothingByDefault() {
        PromptCache none = PromptCache.builder().build();

        assertThat(none.system()).isFalse();
        assertThat(none.tools()).isFalse();
        assertThat(none.throughMessages()).isEmpty();
        assertThat(none.breakpoints()).isZero();
    }

    @Test
    void countsEachMarkedPartAsOneBreakpoint() {
        PromptCache cache =
                PromptCache.builder().system().tools().throughMessage(3).throughMessage(5).build();

        assertThat(cache.breakpoints()).isEqualTo(4);
        assertThat(cache.throughMessages()).containsExactly(3, 5);
    }

    @Test
    void refusesMoreThanTheFourBreakpointsTheVendorAllows() {
        PromptCache.Builder tooMany =
                PromptCache.builder()
                        .system()
                        .tools()
                        .throughMessage(1)
                        .throughMessage(2)
                        .throughMessage(3);

        assertThatThrownBy(tooMany::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("4")
                .hasMessageContaining("5");
    }

    @Test
    void refusesANegativeMessageIndex() {
        assertThatThrownBy(() -> PromptCache.builder().throughMessage(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("-1");
    }

    @Test
    void markingTheSameMessageTwiceIsOneBreakpoint() {
        PromptCache cache = PromptCache.builder().throughMessage(2).throughMessage(2).build();

        assertThat(cache.breakpoints()).isEqualTo(1);
    }

    @Test
    void isAValueSoTwoWithTheSamePartsAreEqual() {
        assertThat(PromptCache.builder().system().throughMessage(1).build())
                .isEqualTo(PromptCache.builder().throughMessage(1).system().build())
                .hasSameHashCodeAs(PromptCache.builder().system().throughMessage(1).build());
    }

    @Test
    void aRequestKeepsItsCacheThroughACopy() {
        PromptCache cache = PromptCache.builder().system().build();
        LLMRequest request = LLMRequest.builder().addUserMessage("hi").promptCache(cache).build();

        assertThat(request.getPromptCache()).isEqualTo(cache);
        assertThat(request.toBuilder().build().getPromptCache()).isEqualTo(cache);
        assertThat(LLMRequest.builder().addUserMessage("hi").build().getPromptCache()).isNull();
    }

    @Test
    void requestsDifferingOnlyInTheirCacheAreNotEqual() {
        LLMRequest plain = LLMRequest.builder().addUserMessage("hi").build();
        LLMRequest cached =
                plain.toBuilder().promptCache(PromptCache.builder().system().build()).build();

        assertThat(cached).isNotEqualTo(plain);
    }
}
