package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.StructuredOutput;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** A wrapper behaves like what it wraps (docs/specs/30-native-json-output, R2). */
class ServedModelRecorderStructuredOutputTest {

    @Test
    void theRecorderSaysWhatItWraps() {
        LLMClient inner =
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

        assertThat(new ServedModelRecorder(inner, served -> {}).structuredOutput(null))
                .isEqualTo(StructuredOutput.JSON_SCHEMA);
    }
}
