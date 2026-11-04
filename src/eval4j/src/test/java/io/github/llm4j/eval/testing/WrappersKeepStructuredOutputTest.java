package io.github.llm4j.eval.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.eval.optimize.LlmCallCounter;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.StructuredOutput;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A wrapper behaves like what it wraps (docs/specs/30-native-json-output, R2): a client that can
 * constrain replies to a schema does not lose that by being metered or counted.
 */
class WrappersKeepStructuredOutputTest {

    private static final LLMClient SCHEMA_CAPABLE =
            new LLMClient() {
                @Override
                public LLMResponse chat(LLMRequest request) {
                    return LLMResponse.builder().content("{}").model("m").build();
                }

                @Override
                public Stream<LLMResponse> chatStream(LLMRequest request) {
                    return Stream.empty();
                }

                @Override
                public StructuredOutput structuredOutput(String model) {
                    return StructuredOutput.JSON_SCHEMA;
                }
            };

    @Test
    void theCallCounterSaysWhatItWraps() {
        assertThat(LlmCallCounter.wrap(SCHEMA_CAPABLE).structuredOutput("m"))
                .isEqualTo(StructuredOutput.JSON_SCHEMA);
    }

    @Test
    void theSpendGuardSaysWhatItWraps() {
        SpendGuard guard = SpendGuard.withPrices(io.github.llm4j.eval.export.Pricing.fromSystem());

        assertThat(guard.guard(SCHEMA_CAPABLE, "m").structuredOutput("m"))
                .isEqualTo(StructuredOutput.JSON_SCHEMA);
    }
}
